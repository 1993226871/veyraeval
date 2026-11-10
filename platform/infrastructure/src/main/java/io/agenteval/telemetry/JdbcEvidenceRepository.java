package io.agenteval.telemetry;
import io.agenteval.catalog.CanonicalJson;
import io.agenteval.identity.*;
import io.agenteval.protocol.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcEvidenceRepository implements EvidenceRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ProtocolCodec codec=new ProtocolCodec();
    public JdbcEvidenceRepository(JdbcClient jdbc,PlatformTransactionManager manager,ObjectMapper json) { this.jdbc=jdbc; tx=new TransactionTemplate(manager); this.json=json; }
    @Override public EvidenceSnapshot freeze(WorkspaceActor actor,UUID trial,boolean includeLate,Instant now) {
        return freeze(actor,trial,includeLate,now,true);
    }
    @Override public EvidenceSnapshot freezeForEvaluation(WorkspaceActor actor,UUID trial,boolean includeLate,Instant now) {
        return freeze(actor,trial,includeLate,now,false);
    }
    private EvidenceSnapshot freeze(WorkspaceActor actor,UUID trial,boolean includeLate,Instant now,boolean scheduleDefault) {
        return tx.execute(transaction -> {
            // Same lock boundary as ingestion and completion: the manifest cannot race a committed ack.
            jdbc.sql("select id from scheduler_guard where id=1 for update").query(Integer.class).single();
            var row=jdbc.sql("select t.status,t.task,t.completion,t.completed_at,e.id experiment_id,e.snapshot,e.suite_version_id,e.status experiment_status from trials t join experiments e on e.id=t.experiment_id where t.id=? and t.workspace_id=? and e.status not in ('DELETING','DELETED') for update of t,e")
                .params(trial,actor.workspaceId()).query((r,n) -> new TrialEvidence(r.getString("status"),tree(r.getString("task")),tree(r.getString("completion")),r.getTimestamp("completed_at")==null?null:r.getTimestamp("completed_at").toInstant(),tree(r.getString("snapshot")),r.getString("experiment_status"),r.getObject("suite_version_id",UUID.class),r.getObject("experiment_id",UUID.class))).optional().orElseThrow(ScopeViolationException::new);
            var first=jdbc.sql("select * from evidence_snapshots where trial_id=? order by ordinal limit 1").param(trial).query(this::map).optional();
            if(first.isPresent() && !includeLate) return first.get();
            if(row.completedAt()==null || Set.of("QUEUED","LEASED","RUNNING").contains(row.status())) throw new EvidencePendingException();
            var manifest=json.createObjectNode(); manifest.set("task",row.task()); manifest.set("completion",row.completion()); manifest.putObject("configuration_ref").put("experiment_id",row.experimentId().toString()).put("content_hash",CanonicalJson.hash(row.experiment())); manifest.put("execution_status",row.status());
            var events=manifest.putArray("events"); var hashes=manifest.putArray("event_hashes"); var issues=manifest.putArray("issues");
            var records=jdbc.sql("select event_json,content_hash,origin from trace_events where workspace_id=? and trial_id=? order by sequence").params(actor.workspaceId(),trial).query((r,n) -> new StoredEvent(tree(r.getString("event_json")),r.getString("content_hash"),r.getString("origin"))).list();
            long expectedSequence=1; boolean gap=false,truncated=false; var starts=new HashMap<String,TraceEvent>(); var ends=new HashSet<String>();
            for(var record:records) {
                var event=decode(record.event()); events.add(record.event()); hashes.addObject().put("event_id",event.eventId().toString()).put("sha256",record.hash()).put("origin",record.origin());
                if(event.sequence()!=expectedSequence) gap=true;
                expectedSequence=event.sequence()==Long.MAX_VALUE?Long.MAX_VALUE:event.sequence()+1;
                truncated|=event.capture().truncated() || event.capture().redacted();
                if(event.eventType()==EventType.SPAN_STARTED) starts.put(event.spanId(),event);
                if(event.eventType()==EventType.SPAN_COMPLETED || event.eventType()==EventType.SPAN_FAILED) ends.add(event.spanId());
            }
            var completion=row.completion();
            if(completion.isNull()) issues.add("missing_completion");
            long finalSequence=completion.path("final_sequence").asLong();
            if(gap || (!records.isEmpty() && records.getLast().event().path("sequence").asLong()!=finalSequence) || (records.isEmpty() && finalSequence!=0)) issues.add("sequence_gap");
            if(completion.path("event_count").asLong()!=records.size()) issues.add("event_count_mismatch");
            if(completion.path("dropped_count").asLong()>0) issues.add("dropped_events");
            if(truncated) issues.add("truncated_or_redacted");
            boolean treeValid=starts.values().stream().filter(e -> e.parentSpanId()==null).count()==1 && starts.keySet().equals(ends);
            var validated=new HashSet<String>();
            for(var span:starts.keySet()) {
                var visiting=new HashSet<String>(); String cursor=span;
                while(cursor!=null && !validated.contains(cursor)) {
                    if(!visiting.add(cursor) || !starts.containsKey(cursor)) { treeValid=false; break; }
                    cursor=starts.get(cursor).parentSpanId();
                }
                validated.addAll(visiting);
            }
            if(!records.isEmpty() && !treeValid) issues.add("incomplete_span_tree");
            var artifacts=manifest.putArray("artifacts"); var unavailable=manifest.putArray("missing_artifacts");
            for(var id:completion.path("artifact_ids")) {
                UUID artifactId=UUID.fromString(id.asText());
                var artifact=jdbc.sql("select id,name,media_type,size_bytes,sha256 from artifacts where id=? and trial_id=? and workspace_id=? and status='AVAILABLE'").params(artifactId,trial,actor.workspaceId())
                    .query((r,n) -> new ArtifactRef(r.getObject("id",UUID.class),r.getString("name"),r.getString("media_type"),r.getLong("size_bytes"),r.getString("sha256"))).optional();
                if(artifact.isPresent()) { try { artifacts.add(codec.tree(codec.encode(artifact.get()))); } catch(java.io.IOException impossible) { throw new IllegalStateException(impossible); } }
                else unavailable.add(id.asText());
            }
            if(!unavailable.isEmpty()) issues.add("missing_artifact");
            if(!issues.isEmpty() && now.isBefore(row.completedAt().plusSeconds(30))) throw new EvidencePendingException();
            var completeness=records.isEmpty()?EvidenceCompleteness.NONE:issues.isEmpty()?EvidenceCompleteness.COMPLETE:EvidenceCompleteness.PARTIAL;
            manifest.put("completeness",completeness.name()); String hash=CanonicalJson.hash(manifest);
            var existing=jdbc.sql("select * from evidence_snapshots where trial_id=? and content_hash=?").params(trial,hash).query(this::map).optional();
            if(existing.isPresent()) return existing.get();
            UUID id=UUID.randomUUID();
            long bytes=jdbc.sql("insert into evidence_snapshots(id,workspace_id,trial_id,content_hash,completeness,manifest,created_at) values (?,?,?,?,?,cast(? as jsonb),?) returning octet_length(manifest::text)").params(id,actor.workspaceId(),trial,hash,completeness.name(),manifest.toString(),Timestamp.from(now)).query(Long.class).single();
            if(bytes>32L*1024*1024) throw new TelemetryLimitException();
            io.agenteval.administration.JdbcResourceAdmission.evidence(jdbc,actor.workspaceId(),bytes);
            jdbc.sql("update trials set evidence_wait_reason=null where id=? and workspace_id=?").params(trial,actor.workspaceId()).update();
            if(scheduleDefault && !isSdkRun(row.experimentId()) && !row.status().equals("CANCELLED") && !row.experimentStatus().equals("CANCELLED")) jdbc.sql("insert into grading_jobs(id,workspace_id,trial_id,snapshot_id,suite_version_id,next_run_at,created_at) values (?,?,?,?,?,?,?)").params(UUID.randomUUID(),actor.workspaceId(),trial,id,row.suiteVersion(),Timestamp.from(now),Timestamp.from(now)).update();
            enqueueSdkRunIfReady(jdbc,row.experimentId(),now);
            return new EvidenceSnapshot(id,trial,hash,completeness,manifest,now);
        });
    }
    private boolean isSdkRun(UUID experiment) {
        return jdbc.sql("select exists(select 1 from sdk_runs where experiment_id=?)").param(experiment).query(Boolean.class).single();
    }
    // The caller holds scheduler_guard. Queue the whole run once every case has frozen evidence.
    public static void enqueueSdkRunIfReady(JdbcClient jdbc,UUID experiment,Instant now) {
        jdbc.sql("""
            insert into grading_jobs(id,workspace_id,trial_id,snapshot_id,suite_version_id,next_run_at,created_at)
            select gen_random_uuid(),t.workspace_id,t.id,s.id,e.suite_version_id,?,?
            from sdk_runs r join experiments e on e.id=r.experiment_id
            join trials t on t.experiment_id=e.id
            join lateral (select id from evidence_snapshots where trial_id=t.id order by ordinal limit 1) s on true
            where e.id=? and r.authorized and e.status='ACTIVE' and t.status<>'CANCELLED'
              and not exists(select 1 from trials pending where pending.experiment_id=e.id
                and not exists(select 1 from evidence_snapshots frozen where frozen.trial_id=pending.id))
              and not exists(select 1 from grading_jobs g where g.trial_id=t.id and g.initial_run)
            """).params(Timestamp.from(now),Timestamp.from(now),experiment).update();
    }
    @Override public EvidenceSnapshot load(WorkspaceActor actor,UUID id) { return jdbc.sql("select s.* from evidence_snapshots s join trials t on t.id=s.trial_id join experiments e on e.id=t.experiment_id where s.id=? and s.workspace_id=? and e.status not in ('DELETING','DELETED')").params(id,actor.workspaceId()).query(this::map).optional().orElseThrow(ScopeViolationException::new); }
    @Override public int freezeReady(int limit,Instant now) {
        var trials=jdbc.sql("select t.id,t.workspace_id,w.owner_user_id from trials t join workspaces w on w.id=t.workspace_id join experiments e on e.id=t.experiment_id where t.completed_at<=? and t.evidence_next_attempt_at<=? and e.status not in ('DELETING','DELETED') and not exists(select 1 from evidence_snapshots s where s.trial_id=t.id) order by t.evidence_last_attempt_at,t.completed_at,t.id limit ?")
            .params(Timestamp.from(now.minusSeconds(30)),Timestamp.from(now),limit).query((r,n) -> new Candidate(r.getObject("id",UUID.class),new WorkspaceActor(r.getObject("owner_user_id",UUID.class),r.getObject("workspace_id",UUID.class),false))).list();
        int frozen=0;
        for(var candidate:trials) {
            try { freeze(candidate.actor(),candidate.id(),false,now); frozen++; }
            catch(io.agenteval.workers.CapacityExceededException limited) { markWait(candidate,"STORAGE_LIMIT",now); }
            catch(TelemetryLimitException limited) { markWait(candidate,"EVIDENCE_BYTE_LIMIT",now); }
            catch(ScopeViolationException deletedDuringScan) { /* A concurrent delete wins; no new snapshot is published. */ }
        }
        return frozen;
    }
    private void markWait(Candidate candidate,String reason,Instant now) {
        jdbc.sql("update trials set evidence_wait_reason=?,evidence_last_attempt_at=?,evidence_next_attempt_at=? where id=? and workspace_id=? and exists(select 1 from experiments e where e.id=trials.experiment_id and e.status not in ('DELETING','DELETED'))").params(reason,Timestamp.from(now),Timestamp.from(now.plusSeconds(30)),candidate.id(),candidate.actor().workspaceId()).update();
    }
    private TraceEvent decode(JsonNode event) { try { return codec.decode(event.toString(),TraceEvent.class); } catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); } }
    private JsonNode tree(String value) { if(value==null) return NullNode.instance; try { return json.readTree(value); } catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); } }
    private EvidenceSnapshot map(java.sql.ResultSet r,int n) throws java.sql.SQLException { return new EvidenceSnapshot(r.getObject("id",UUID.class),r.getObject("trial_id",UUID.class),r.getString("content_hash"),EvidenceCompleteness.valueOf(r.getString("completeness")),tree(r.getString("manifest")),r.getTimestamp("created_at").toInstant()); }
    private record TrialEvidence(String status,JsonNode task,JsonNode completion,Instant completedAt,JsonNode experiment,String experimentStatus,UUID suiteVersion,UUID experimentId) {}
    private record StoredEvent(JsonNode event,String hash,String origin) {}
    private record Candidate(UUID id,WorkspaceActor actor) {}
}
