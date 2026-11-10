package io.agenteval.evaluation;
import io.agenteval.catalog.*;
import io.agenteval.identity.*;
import io.agenteval.telemetry.EvidenceSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcReevaluationRepository implements ReevaluationRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    public JdbcReevaluationRepository(JdbcClient jdbc,PlatformTransactionManager manager) { this.jdbc=jdbc; transactions=new TransactionTemplate(manager); }
    public UUID create(WorkspaceActor actor,UUID trial,UUID suite,boolean includeLate,String key,JsonNode configuration,Supplier<EvidenceSnapshot> freeze,Instant now) {
        var request=JsonNodeFactory.instance.objectNode().put("trial",trial.toString()).put("suite",suite.toString()).put("include_late",includeLate); String hash=CanonicalJson.hash(request);
        return transactions.execute(status -> {
            jdbc.sql("select id from scheduler_guard where id=1 for update").query(Integer.class).single();
            var prior=jdbc.sql("select id,request_hash from grading_jobs where workspace_id=? and trial_id=? and idempotency_key=?").params(actor.workspaceId(),trial,key).query((row,index)->Map.entry(row.getObject("id",UUID.class),row.getString("request_hash"))).optional();
            if(prior.isPresent()) { if(!prior.get().getValue().equals(hash)) throw new CatalogConflictException(); return prior.get().getKey(); }
            var state=jdbc.sql("select t.status execution,e.status experiment from trials t join experiments e on e.id=t.experiment_id where t.id=? and t.workspace_id=? for update of t,e").params(trial,actor.workspaceId())
                .query((row,index)->Map.entry(row.getString("execution"),row.getString("experiment"))).optional().orElseThrow(ScopeViolationException::new);
            if(Set.of("QUEUED","LEASED","RUNNING","CANCELLED").contains(state.getKey()) || Set.of("CANCELLED","DELETING","DELETED").contains(state.getValue())) throw new CatalogConflictException();
            if (jdbc.sql("select exists(select 1 from sdk_runs r join trials t on t.experiment_id=r.experiment_id where t.id=? and r.workspace_id=? and not exists(select 1 from grading_jobs g where g.trial_id=t.id and g.initial_run))")
                    .params(trial,actor.workspaceId()).query(Boolean.class).single()) throw new CatalogConflictException();
            // The nested freeze joins this transaction, after the idempotency check, without an implicit extra run.
            var snapshot=freeze.get(); if(!snapshot.trialId().equals(trial)) throw new IllegalArgumentException("Foreign reevaluation snapshot");
            UUID id=UUID.randomUUID();
            jdbc.sql("insert into grading_jobs(id,workspace_id,trial_id,snapshot_id,suite_version_id,initial_run,run_config,idempotency_key,request_hash,next_run_at,created_at,purpose) values(?,?,?,?,?,false,cast(? as jsonb),?,?,?,?,'REEVALUATION')")
                .params(id,actor.workspaceId(),trial,snapshot.id(),suite,configuration.toString(),key,hash,Timestamp.from(now),Timestamp.from(now)).update();
            return id;
        });
    }
}
