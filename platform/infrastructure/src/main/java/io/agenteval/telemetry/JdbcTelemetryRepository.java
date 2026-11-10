package io.agenteval.telemetry;
import io.agenteval.catalog.CanonicalJson;
import io.agenteval.identity.*;
import io.agenteval.experiments.LeaseRepository;
import io.agenteval.protocol.*;
import io.agenteval.workers.WorkerPrincipal;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Repository
public class JdbcTelemetryRepository implements TelemetryRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final LeaseRepository leases;
    private final ObjectMapper json;
    private final ProtocolCodec codec=new ProtocolCodec();
    private final SpanProjector projector=new SpanProjector();
    public JdbcTelemetryRepository(JdbcClient jdbc,PlatformTransactionManager manager,LeaseRepository leases,ObjectMapper json) { this.jdbc=jdbc; tx=new TransactionTemplate(manager); this.leases=leases; this.json=json; }
    @Override public BatchAck ingest(WorkerPrincipal p,UUID trialId,String token,List<TraceEvent> events,Instant now) {
        return tx.execute(status -> {
            leases.validateUploadAttempt(p,trialId,token,now);
            return persist(p.workspaceId(),trialId,events,now);
        });
    }
    /** For a validated SDK run inside the owning transaction and scheduler lock. */
    public BatchAck persist(UUID workspace,UUID trialId,List<TraceEvent> events,Instant now) {
        String trace=jdbc.sql("select trace_id from trials where id=? and workspace_id=?").params(trialId,workspace).query(String.class).single();
        int count=jdbc.sql("select count(*) from trace_events where trial_id=? and workspace_id=?").params(trialId,workspace).query(Integer.class).single();
        var accepted=new ArrayList<UUID>();
        for(var event:events) {
            if(!trace.equals(event.traceId())) throw new TelemetryConflictException();
            var payload=toTree(event); String hash=CanonicalJson.hash(payload);
            var old=existingHash(workspace,event.eventId());
            if(old.isPresent()) { if(!old.get().equals(hash)) throw new TelemetryConflictException(); accepted.add(event.eventId()); continue; }
            if(count>=10000) throw new TelemetryLimitException();
            var inserted=jdbc.sql("insert into trace_events(workspace_id,trial_id,event_id,sequence,event_json,content_hash,received_at) values (?,?,?,?,cast(? as jsonb),?,?) on conflict do nothing returning octet_length(event_json::text)")
                .params(workspace,trialId,event.eventId(),event.sequence(),payload.toString(),hash,Timestamp.from(now)).query(Long.class).optional();
            if(inserted.isEmpty()) {
                if(!existingHash(workspace,event.eventId()).map(hash::equals).orElse(false)) throw new TelemetryConflictException();
            } else {
                long bytes=inserted.get();
                if(jdbc.sql("update trial_evidence_usage set event_bytes=event_bytes+? where trial_id=? and workspace_id=? and event_bytes<=16777216-?").params(bytes,trialId,workspace,bytes).update()!=1) throw new TelemetryLimitException();
                // Includes a conservative allowance for the materialized Span projection.
                io.agenteval.administration.JdbcResourceAdmission.evidence(jdbc,workspace,Math.multiplyExact(bytes,2));
                count++; project(workspace,trialId,event);
            }
            accepted.add(event.eventId());
        }
        return new BatchAck(accepted);
    }
    private Optional<String> existingHash(UUID workspace,UUID event) { return jdbc.sql("select content_hash from trace_events where workspace_id=? and event_id=?").params(workspace,event).query(String.class).optional(); }
    private void project(UUID workspace,UUID trial,TraceEvent event) {
        if(event.eventType()==EventType.LOG || event.eventType()==EventType.ARTIFACT_CREATED) return;
        jdbc.sql("insert into spans(workspace_id,trial_id,span_id,parent_span_id,name,kind) values (?,?,?,?,?,?) on conflict do nothing")
            .params(workspace,trial,event.spanId(),event.parentSpanId(),event.name(),event.spanKind().wire()).update();
        var current=jdbc.sql("select * from spans where workspace_id=? and trial_id=? and span_id=? for update").params(workspace,trial,event.spanId()).query(this::mapSpan).single();
        var next=projector.project(current,event);
        jdbc.sql("update spans set status=?,started_at=?,ended_at=?,input=cast(? as jsonb),output=cast(? as jsonb),duration_ms=? where id=?")
            .params(next.status(),timestamp(next.startedAt()),timestamp(next.endedAt()),next.input().toString(),next.output().toString(),next.durationMs(),next.id()).update();
    }
    @Override public TracePage query(WorkspaceActor actor,UUID trialId,String cursor,int limit) {
        if(jdbc.sql("select t.id from trials t join experiments e on e.id=t.experiment_id where t.id=? and t.workspace_id=? and e.status not in ('DELETING','DELETED')").params(trialId,actor.workspaceId()).query(UUID.class).optional().isEmpty()) throw new ScopeViolationException();
        long last=0,upper;
        if(cursor==null || cursor.isBlank()) {
            upper=jdbc.sql("select coalesce(max(id),0) from spans where workspace_id=? and trial_id=?").params(actor.workspaceId(),trialId).query(Long.class).single();
        } else {
            try {
                if(cursor.length()>512) throw new IllegalArgumentException();
                var pieces=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
                if(pieces.length!=4 || !actor.workspaceId().toString().equals(pieces[0]) || !trialId.toString().equals(pieces[1])) throw new IllegalArgumentException();
                last=Long.parseLong(pieces[2]); upper=Long.parseLong(pieces[3]); if(last<0 || upper<last) throw new IllegalArgumentException();
            } catch(IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid trace cursor"); }
        }
        var found=jdbc.sql("select * from spans where workspace_id=? and trial_id=? and id>? and id<=? order by id limit ?")
            .params(actor.workspaceId(),trialId,last,upper,limit+1).query(this::mapSpan).list();
        boolean more=found.size()>limit; var page=found.subList(0,Math.min(found.size(),limit)); String next=null;
        if(more) next=Base64.getUrlEncoder().withoutPadding().encodeToString((actor.workspaceId()+"|"+trialId+"|"+page.getLast().id()+"|"+upper).getBytes(StandardCharsets.UTF_8));
        return new TracePage(page,next);
    }
    private SpanView mapSpan(java.sql.ResultSet row,int n) throws java.sql.SQLException {
        var start=row.getTimestamp("started_at"); var end=row.getTimestamp("ended_at");
        return new SpanView(row.getLong("id"),row.getString("span_id"),row.getString("parent_span_id"),row.getString("name"),row.getString("kind"),row.getString("status"),start==null?null:start.toInstant(),end==null?null:end.toInstant(),tree(row.getString("input")),tree(row.getString("output")),row.getObject("duration_ms",Long.class),row.getString("origin"));
    }
    private JsonNode tree(String value) { try { return json.readTree(value); } catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); } }
    private JsonNode toTree(TraceEvent event) { try { return codec.tree(codec.encode(event)); } catch(java.io.IOException invalid) { throw new IllegalArgumentException(invalid); } }
    private Timestamp timestamp(Instant value) { return value==null?null:Timestamp.from(value); }
}
