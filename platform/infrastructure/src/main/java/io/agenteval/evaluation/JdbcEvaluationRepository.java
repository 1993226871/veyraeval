package io.agenteval.evaluation;
import io.agenteval.catalog.CanonicalJson;
import io.agenteval.identity.*;
import io.agenteval.protocol.*;
import io.agenteval.reporting.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcEvaluationRepository implements EvaluationRepository,ReportRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final ObjectMapper json;
    private final long reportBodyLimit;
    private final ProtocolCodec codec=new ProtocolCodec();
    @org.springframework.beans.factory.annotation.Autowired
    public JdbcEvaluationRepository(JdbcClient jdbc,PlatformTransactionManager manager,ObjectMapper json) { this(jdbc,manager,json,16L*1024*1024); }
    public JdbcEvaluationRepository(JdbcClient jdbc,PlatformTransactionManager manager,ObjectMapper json,long reportBodyLimit) {
        if(reportBodyLimit<1 || reportBodyLimit>16L*1024*1024) throw new IllegalArgumentException("Invalid report body budget");
        this.reportBodyLimit=reportBodyLimit;
        this.jdbc=jdbc; tx=new TransactionTemplate(manager); this.json=json;
        readTx=new TransactionTemplate(manager); readTx.setReadOnly(true); readTx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public int schedule(int limit,Instant now) {
        return tx.execute(transaction -> {
            guard(); cancelJobs(now);
            var runs=jdbc.sql("select g.id,g.workspace_id,g.trial_id,g.snapshot_id,coalesce(g.run_config,s.manifest->'experiment_snapshot',e.snapshot) configuration from grading_jobs g join evidence_snapshots s on s.id=g.snapshot_id join trials t on t.id=g.trial_id join experiments e on e.id=t.experiment_id where g.status='QUEUED' and e.status not in ('CANCELLED','DELETING','DELETED') order by g.created_at,g.id limit ? for update of g skip locked")
                .param(limit).query((r,n) -> new PendingRun(r.getObject("id",UUID.class),r.getObject("workspace_id",UUID.class),r.getObject("trial_id",UUID.class),r.getObject("snapshot_id",UUID.class),tree(r.getString("configuration")))).list();
            for(var run:runs) {
                var frozen=run.manifest(); var entries=entries(frozen.path("suite"));
                if(entries.stream().noneMatch(EvaluationEntry::requiredToPass)) throw new IllegalStateException("Frozen suite lacks required criterion");
                var definitions=new HashMap<UUID,JsonNode>(); for(var item:frozen.path("evaluators")) definitions.put(UUID.fromString(item.path("id").asText()),item.path("content"));
                int prior=jdbc.sql("select count(*) from evaluation_jobs where run_id=?").param(run.id()).query(Integer.class).single();
                int needed=(int)entries.stream().map(EvaluationEntry::evaluatorVersion).distinct().count()-prior;
                if(!io.agenteval.administration.JdbcResourceAdmission.evaluationJobs(jdbc,run.workspace(),needed)) {
                    jdbc.sql("update grading_jobs set status='WAITING_RESOURCES' where id=?").param(run.id()).update(); continue;
                }
                for(var version:entries.stream().map(EvaluationEntry::evaluatorVersion).distinct().toList()) {
                    var definition=definitions.get(version); if(definition==null) throw new IllegalStateException("Missing frozen evaluator");
                    jdbc.sql("insert into evaluation_jobs(id,run_id,workspace_id,trial_id,snapshot_id,evaluator_version,definition,created_at,next_run_at) values (?,?,?,?,?,?,cast(? as jsonb),?,?) on conflict(run_id,evaluator_version) do nothing")
                        .params(UUID.randomUUID(),run.id(),run.workspace(),run.trial(),run.snapshot(),version,definition.toString(),Timestamp.from(now),Timestamp.from(now)).update();
                }
                jdbc.sql("update grading_jobs set status='RUNNING',scheduled_at=? where id=?").params(Timestamp.from(now),run.id()).update();
            }
            return runs.size();
        });
    }
    @Override public Optional<EvaluatorJob> claim(Instant now) {
        return tx.execute(transaction -> {
            guard(); cancelJobs(now);
            jdbc.sql("update evaluation_jobs set status='QUEUED',lease_token=null,lease_expires_at=null where status='RUNNING' and lease_expires_at<=?").param(Timestamp.from(now)).update();
            jdbc.sql("""
                update evaluation_jobs j set status='DEFERRED',wait_reason='PYTHON_RESOURCE_LIMIT',next_run_at=?
                from workspace_resource_limits l join workspace_resource_usage u on u.workspace_id=l.workspace_id
                where j.workspace_id=l.workspace_id and j.definition->>'type'='PYTHON' and not j.python_budget_reserved
                  and j.status in ('QUEUED','DEFERRED') and j.next_run_at<=? and u.python_reserved_seconds>l.max_python_seconds-30
                """
            ).params(Timestamp.from(now.plusSeconds(30)),Timestamp.from(now)).update();
            var candidate=jdbc.sql("select j.*,w.owner_user_id from evaluation_jobs j join workspaces w on w.id=j.workspace_id join grading_jobs g on g.id=j.run_id where j.status in ('QUEUED','WAITING_QUOTA','DEFERRED') and j.next_run_at<=? and g.status in ('RUNNING','WAITING_QUOTA') order by case j.definition->>'type' when 'RULE' then 0 when 'PYTHON' then 1 else 2 end,j.created_at,j.id limit 1 for update of j skip locked").param(Timestamp.from(now)).query(this::mapJob).optional();
            if(candidate.isEmpty()) return Optional.empty(); var old=candidate.get();
            if(old.job().definition().type().equals("PYTHON") && !io.agenteval.administration.JdbcResourceAdmission.pythonExecution(jdbc,old.job().owner().workspaceId(),old.job().id())) {
                jdbc.sql("update evaluation_jobs set status='DEFERRED',wait_reason='PYTHON_RESOURCE_LIMIT',next_run_at=? where id=?").params(Timestamp.from(now.plusSeconds(30)),old.job().id()).update(); return Optional.empty();
            }
            UUID token=UUID.randomUUID(); Instant expires=now.plusSeconds(60);
            jdbc.sql("update evaluation_jobs set status='RUNNING',lease_token=?,lease_expires_at=?,attempts=attempts+1 where id=?").params(token,Timestamp.from(expires),old.job().id()).update();
            jdbc.sql("update grading_jobs set status='RUNNING' where id=?").param(old.job().runId()).update();
            var job=old.job(); return Optional.of(new EvaluatorJob(job.id(),job.runId(),job.trialId(),job.snapshotId(),job.owner(),job.definition(),token,expires));
        });
    }
    @Override public boolean complete(EvaluatorJob job,CriterionResult result,Instant now) {
        return tx.execute(transaction -> {
            guard(); cancelJobs(now);
            var saved=jdbc.sql("select j.*,w.owner_user_id from evaluation_jobs j join workspaces w on w.id=j.workspace_id where j.id=? and j.workspace_id=? for update of j").params(job.id(),job.owner().workspaceId()).query(this::mapJob).optional();
            if(saved.isEmpty()) return false; var row=saved.get();
            if(!Objects.equals(row.job().leaseToken(),job.leaseToken()) || !row.job().snapshotId().equals(job.snapshotId()) || !row.job().runId().equals(job.runId()) || !row.job().trialId().equals(job.trialId()) || !row.job().definition().evaluatorVersion().equals(result.evaluatorVersion()) || !row.job().definition().criterionId().equals(result.criterionId())) return false;
            var payload=toTree(result); String hash=CanonicalJson.hash(payload);
            if(row.status().equals("DONE")) return jdbc.sql("select content_hash from evaluation_results where job_id=?").param(job.id()).query(String.class).optional().map(hash::equals).orElse(false);
            if(!row.status().equals("RUNNING") || !row.job().leaseExpiresAt().isAfter(now)) return false;
            if(result.status()==CriterionStatus.SCORED && result.passed()!=(result.score().compareTo(row.job().definition().threshold())>=0)) throw new IllegalArgumentException("Result threshold mismatch");
            var manifest=jdbc.sql("select manifest from evidence_snapshots where id=? and workspace_id=?").params(job.snapshotId(),job.owner().workspaceId()).query(String.class).single(); verifyReferences(tree(manifest),job.snapshotId(),result.evidenceRefs());
            int inserted=jdbc.sql("insert into evaluation_results(job_id,workspace_id,run_id,snapshot_id,trial_id,evaluator_version,payload,content_hash,created_at) values (?,?,?,?,?,?,cast(? as jsonb),?,?) on conflict(job_id) do nothing")
                .params(job.id(),job.owner().workspaceId(),job.runId(),job.snapshotId(),job.trialId(),result.evaluatorVersion(),payload.toString(),hash,Timestamp.from(now)).update();
            if(inserted!=1) return false;
            jdbc.sql("update evaluation_jobs set status='DONE',completed_at=? where id=?").params(Timestamp.from(now),job.id()).update();
            refreshRun(job.runId(),now); return true;
        });
    }
    private void guard() { jdbc.sql("select id from scheduler_guard where id=1 for update").query(Integer.class).single(); }
    @Override public boolean active(EvaluatorJob job,Instant now) {
        return tx.execute(status -> { guard(); cancelJobs(now); return activeRow(job,now); });
    }
    private boolean activeRow(EvaluatorJob job,Instant now) {
        return jdbc.sql("select count(*) from evaluation_jobs where id=? and workspace_id=? and run_id=? and snapshot_id=? and trial_id=? and status='RUNNING' and lease_token=? and lease_expires_at>?")
            .params(job.id(),job.owner().workspaceId(),job.runId(),job.snapshotId(),job.trialId(),job.leaseToken(),Timestamp.from(now)).query(Long.class).single()==1;
    }
    @Override public boolean defer(EvaluatorJob job,CriterionResult pending,String reason,Instant now,Instant retryAt) {
        if(reason==null || reason.length()>1000 || !retryAt.isAfter(now) || retryAt.isAfter(now.plusSeconds(300)) || pending!=null && pending.status()!=CriterionStatus.WAITING_QUOTA) throw new IllegalArgumentException("Invalid evaluation deferral");
        return tx.execute(status -> {
            guard(); cancelJobs(now); if(!activeRow(job,now)) return false;
            if(pending!=null && (!pending.criterionId().equals(job.definition().criterionId()) || !pending.evaluatorVersion().equals(job.definition().evaluatorVersion()))) throw new IllegalArgumentException("Foreign pending criterion");
            jdbc.sql("update evaluation_jobs set status=?,lease_token=null,lease_expires_at=null,next_run_at=?,last_result=cast(? as jsonb),wait_reason=? where id=?")
                .param(1,pending==null?"DEFERRED":"WAITING_QUOTA").param(2,Timestamp.from(retryAt)).param(3,pending==null?null:toTree(pending).toString(),java.sql.Types.VARCHAR).param(4,reason).param(5,job.id()).update();
            refreshRun(job.runId(),now); return true;
        });
    }
    private void refreshRun(UUID run,Instant now) {
        jdbc.sql("""
            update grading_jobs g set status=case
              when exists(select 1 from evaluation_jobs j where j.run_id=g.id and j.status in ('QUEUED','RUNNING','DEFERRED')) then 'RUNNING'
              when exists(select 1 from evaluation_jobs j where j.run_id=g.id and j.status='WAITING_QUOTA') then 'WAITING_QUOTA'
              when exists(select 1 from evaluation_results r where r.run_id=g.id and r.payload->>'status'<>'SCORED') then 'PARTIAL' else 'COMPLETED' end,
              completed_at=case when exists(select 1 from evaluation_jobs j where j.run_id=g.id and j.status in ('QUEUED','RUNNING','DEFERRED','WAITING_QUOTA')) then null else cast(? as timestamptz) end
            where g.id=? and g.status<>'CANCELLED'
            """).params(Timestamp.from(now),run).update();
    }
    private void cancelJobs(Instant now) {
        jdbc.sql("update grading_jobs g set status='CANCELLED',completed_at=? from trials t join experiments e on e.id=t.experiment_id where g.trial_id=t.id and g.status in ('QUEUED','RUNNING','WAITING_QUOTA','WAITING_RESOURCES') and (t.status='CANCELLED' or e.status in ('CANCELLED','DELETING','DELETED'))").param(Timestamp.from(now)).update();
        jdbc.sql("update evaluation_jobs j set status='CANCELLED',completed_at=? from grading_jobs g where j.run_id=g.id and g.status='CANCELLED' and j.status in ('QUEUED','RUNNING','WAITING_QUOTA','DEFERRED')").param(Timestamp.from(now)).update();
    }
    private void verifyReferences(JsonNode manifest,UUID snapshot,List<String> refs) {
        var allowed=new HashSet<String>(); allowed.add("snapshot:"+snapshot);
        if(!manifest.path("task").path("instruction").asText().isBlank()) allowed.add("task_instruction");
        if(manifest.path("completion").has("output")) allowed.add("output"); if(manifest.path("task").has("expected")) allowed.add("expected"); if(manifest.path("task").has("input")) allowed.add("task_input");
        for(var event:manifest.path("events")) allowed.add("event:"+event.path("event_id").asText()); for(var artifact:manifest.path("artifacts")) allowed.add("artifact:"+artifact.path("artifact_id").asText());
        if(refs.size()>1000 || refs.stream().anyMatch(ref -> !allowed.contains(ref))) throw new IllegalArgumentException("Unknown snapshot reference");
    }
    @Override public ReportData load(WorkspaceActor actor,UUID experimentId) {
        return readTx.execute(transaction -> loadReport(actor,experimentId,false,null,null));
    }
    @Override public ReportData summary(WorkspaceActor actor,UUID experimentId) { return readTx.execute(transaction -> loadReport(actor,experimentId,true,null,null)); }
    @Override public ReportRepository.PageData page(WorkspaceActor actor,UUID experimentId,int offset,int limit) {
        if(offset<0 || offset>1000 || limit<1 || limit>100) throw new IllegalArgumentException("Invalid report page");
        return readTx.execute(transaction -> {
            var data=loadReport(actor,experimentId,false,offset,limit);
            int count=jdbc.sql("select count(*) from trials where experiment_id=? and workspace_id=?").params(experimentId,actor.workspaceId()).query(Integer.class).single();
            if(offset>count) throw new IllegalArgumentException("Invalid report cursor");
            return new ReportRepository.PageData(data,count);
        });
    }
    private String suiteProjection(String source) {
        return "(select jsonb_build_object('entries',coalesce(jsonb_agg(jsonb_build_object('evaluator_version',item->'evaluator_version','dimension',item->'dimension','required_to_pass',item->'required_to_pass','weight',item->'weight')),'[]'::jsonb)) from jsonb_array_elements(coalesce(("+source+")->'entries','[]'::jsonb)) item)";
    }
    private ReportData loadReport(WorkspaceActor actor,UUID experimentId,boolean summary,Integer offset,Integer limit) {
        String pageFilter=offset==null?"":" and t.id in (select p.id from trials p where p.experiment_id=t.experiment_id and p.workspace_id=t.workspace_id order by p.case_index,p.attempt limit "+limit+" offset "+offset+")";
        var experiment=jdbc.sql("select dataset_version_id,suite_version_id,agent_version,status,jsonb_build_object('suite',"+suiteProjection("snapshot->'suite'")+") snapshot from experiments where id=? and workspace_id=? and status not in ('DELETING','DELETED')").params(experimentId,actor.workspaceId())
            .query((r,n) -> new ExperimentSource(r.getObject("dataset_version_id",UUID.class),r.getObject("suite_version_id",UUID.class),r.getString("agent_version"),r.getString("status"),tree(r.getString("snapshot")))).optional().orElseThrow(ScopeViolationException::new);
        String scoreQuery="""
            with latest as (select distinct on (g.trial_id) g.id from grading_jobs g join trials t on t.id=g.trial_id where t.experiment_id=? and t.workspace_id=? and g.purpose<>'PREVIEW' %s order by g.trial_id,g.run_ordinal desc)
            select r.run_id,r.evaluator_version,r.payload from evaluation_results r join latest l on l.id=r.run_id
            union all select j.run_id,j.evaluator_version,j.last_result payload from evaluation_jobs j join latest l on l.id=j.run_id where j.last_result is not null and j.status in ('WAITING_QUOTA','RUNNING')
            """.formatted(pageFilter);
        if(!summary && jdbc.sql("select coalesce(sum(octet_length(payload::text)),0) from ("+scoreQuery+") bounded").params(experimentId,actor.workspaceId()).query(Long.class).single()>reportBodyLimit) throw new io.agenteval.administration.ResourceReadLimitException();
        String projection=summary?"select run_id,evaluator_version,jsonb_set(jsonb_set(jsonb_set(payload,'{criterion_id}',to_jsonb(left(payload->>'criterion_id',128))),'{reason}','\"Summary projection\"'::jsonb),'{evidence_refs}','[]'::jsonb) payload from ("+scoreQuery+") scalar":scoreQuery;
        var scores=jdbc.sql(projection).params(experimentId,actor.workspaceId()).query((r,n) -> new ResultRow(r.getObject("run_id",UUID.class),r.getObject("evaluator_version",UUID.class),decode(r.getString("payload")))).list();
        var byRun=new HashMap<UUID,Map<UUID,CriterionResult>>(); for(var score:scores) byRun.computeIfAbsent(score.run(),key -> new HashMap<>()).put(score.version(),score.result());
        var trials=jdbc.sql("""
            select t.id,t.case_index,t.attempt,t.status,t.started_at,t.evidence_wait_reason,g.id run_id,coalesce(g.snapshot_id,(select s.id from evidence_snapshots s where s.trial_id=t.id order by s.ordinal limit 1)) snapshot_id,g.suite_version_id,%s suite_config,g.status evaluation_state,
              (select case when count(distinct value->>'span_id')=1
                and count(*) filter(where value->>'event_type'='span.started')>=1
                and count(*) filter(where value->>'event_type' in ('span.completed','span.failed'))=1
                then max(case when value->>'event_type' in ('span.completed','span.failed') and jsonb_typeof(value#>'{payload,duration_ms}')='number' then value#>>'{payload,duration_ms}' end)
                else null end from jsonb_array_elements(coalesce(g.manifest->'events','[]'::jsonb))
                where value->>'span_kind'='agent' and value->'parent_span_id'='null'::jsonb
                and value->>'event_type' in ('span.started','span.completed','span.failed')) duration_ms
            from trials t left join lateral
              (select j.*,e.manifest,coalesce(j.run_config->'suite',e.manifest#>'{experiment_snapshot,suite}',parent.snapshot->'suite') suite_config from grading_jobs j join evidence_snapshots e on e.id=j.snapshot_id join experiments parent on parent.id=t.experiment_id where j.trial_id=t.id and j.purpose<>'PREVIEW' order by j.run_ordinal desc limit 1) g on true
            where t.experiment_id=? and t.workspace_id=? %s order by t.case_index,t.attempt
            """.formatted(suiteProjection("g.suite_config"),pageFilter))
            .params(experimentId,actor.workspaceId()).query((r,n) -> {
                UUID run=r.getObject("run_id",UUID.class); var start=r.getTimestamp("started_at"); String status=r.getString("evaluation_state"); if(status==null || status.equals("QUEUED")) status="PENDING";
                return new ReportData.TrialEvaluation(r.getObject("id",UUID.class),r.getInt("case_index"),r.getInt("attempt"),TrialExecutionStatus.valueOf(r.getString("status")),start==null?null:start.toInstant(),run,r.getObject("snapshot_id",UUID.class),status,byRun.getOrDefault(run,Map.of()),duration(r.getString("duration_ms")),r.getObject("suite_version_id",UUID.class),r.getString("suite_config")==null?List.of():entries(tree(r.getString("suite_config"))),r.getString("evidence_wait_reason"));
            }).list();
        return new ReportData(experimentId,experiment.dataset(),experiment.suite(),experiment.agentVersion(),experiment.status(),entries(experiment.snapshot().path("suite")),trials);
    }
    private Long duration(String value) {
        if(value==null || value.length()>256) return null;
        try { var number=new java.math.BigDecimal(value); if(number.signum()<0 || number.precision()>128 || Math.abs((long)number.scale())>128) return null; return number.longValueExact(); }
        catch(NumberFormatException | ArithmeticException invalid) { return null; }
    }
    private List<EvaluationEntry> entries(JsonNode suite) {
        var result=new ArrayList<EvaluationEntry>(); for(var entry:suite.path("entries")) result.add(new EvaluationEntry(UUID.fromString(entry.path("evaluator_version").asText()),entry.path("dimension").asText(),entry.path("required_to_pass").asBoolean(),entry.path("weight").decimalValue())); return List.copyOf(result);
    }
    private EvaluatorDefinition definition(UUID version,JsonNode content) { var required=new ArrayList<String>(); content.path("required_evidence").forEach(value -> required.add(value.asText())); return new EvaluatorDefinition(version,content.path("criterion_id").asText(),content.path("type").asText(),required,content.path("threshold").decimalValue(),content.path("config")); }
    private JobRow mapJob(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        UUID workspace=r.getObject("workspace_id",UUID.class); var expiry=r.getTimestamp("lease_expires_at");
        var job=new EvaluatorJob(r.getObject("id",UUID.class),r.getObject("run_id",UUID.class),r.getObject("trial_id",UUID.class),r.getObject("snapshot_id",UUID.class),new WorkspaceActor(r.getObject("owner_user_id",UUID.class),workspace,false),definition(r.getObject("evaluator_version",UUID.class),tree(r.getString("definition"))),r.getObject("lease_token",UUID.class),expiry==null?null:expiry.toInstant()); return new JobRow(job,r.getString("status"));
    }
    private JsonNode tree(String value) { try { return json.readTree(value); } catch(java.io.IOException invalid) { throw new IllegalStateException("Invalid persisted evaluation data",invalid); } }
    private JsonNode toTree(CriterionResult result) { try { return codec.tree(codec.encode(result)); } catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); } }
    private CriterionResult decode(String payload) { try { return codec.decode(payload,CriterionResult.class); } catch(java.io.IOException invalid) { throw new IllegalStateException("Invalid persisted result",invalid); } }
    private record PendingRun(UUID id,UUID workspace,UUID trial,UUID snapshot,JsonNode manifest) {}
    private record JobRow(EvaluatorJob job,String status) {}
    private record ResultRow(UUID run,UUID version,CriterionResult result) {}
    private record ExperimentSource(UUID dataset,UUID suite,String agentVersion,String status,JsonNode snapshot) {}
}
