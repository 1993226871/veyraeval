package io.agenteval.administration;

import io.agenteval.catalog.ResourceKind;
import io.agenteval.identity.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWorkspaceListings implements WorkspaceListings {
    private final JdbcClient jdbc;
    public JdbcWorkspaceListings(JdbcClient jdbc) { this.jdbc=jdbc; }
    @Override public List<ResourceSummary> catalog(WorkspaceActor actor,ResourceKind kind,int offset,int limit) {
        WorkspaceListings.page(offset,limit);
        return jdbc.sql("select id,kind,name,revision,archived from catalog_resources where workspace_id=? and kind=? and not archived order by created_at,id limit ? offset ?")
            .params(actor.workspaceId(),kind.name(),limit,offset).query((r,n) -> new ResourceSummary(r.getObject("id",UUID.class),ResourceKind.valueOf(r.getString("kind")),r.getString("name"),r.getLong("revision"),r.getBoolean("archived"))).list();
    }
    private static final String COLUMNS="e.id,e.workspace_id,e.agent_id,e.agent_version,e.dataset_version_id,e.suite_version_id,e.repeat_count,e.deadline,e.status,(e.snapshot->>'source_experiment_id')::uuid source_experiment_id,"
        +"a.name agent_name,d.name dataset_name,d.number dataset_version_number,s.name suite_name,s.number suite_version_number,"
        +"jsonb_array_length(e.snapshot->'dataset'->'cases') case_count,jsonb_array_length(e.snapshot->'dataset'->'cases')*e.repeat_count task_count,e.created_at,r.project_id sdk_project_id,p.name sdk_project,r.namespace,r.name run_name,r.authorized evaluation_authorized";
    private static final String FROM=" from experiments e join agents a on a.id=e.agent_id and a.workspace_id=e.workspace_id "
        +"join catalog_versions d on d.id=e.dataset_version_id and d.workspace_id=e.workspace_id "
        +"join catalog_versions s on s.id=e.suite_version_id and s.workspace_id=e.workspace_id left join sdk_runs r on r.experiment_id=e.id left join sdk_projects p on p.id=r.project_id ";
    @Override public List<ExperimentSummary> experiments(WorkspaceActor actor,int offset,int limit) {
        WorkspaceListings.page(offset,limit);
        return jdbc.sql("select "+COLUMNS+FROM+"where e.workspace_id=? and e.status not in ('DELETING','DELETED') order by e.created_at desc,e.id limit ? offset ?").params(actor.workspaceId(),limit,offset).query(this::map).list();
    }
    @Override public ExperimentSummary experiment(WorkspaceActor actor,UUID id) {
        return jdbc.sql("select "+COLUMNS+FROM+"where e.id=? and e.workspace_id=? and e.status not in ('DELETING','DELETED')").params(id,actor.workspaceId()).query(this::map).optional().orElseThrow(ScopeViolationException::new);
    }
    private ExperimentSummary map(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new ExperimentSummary(r.getObject("id",UUID.class),r.getObject("workspace_id",UUID.class),r.getObject("agent_id",UUID.class),r.getString("agent_version"),r.getObject("dataset_version_id",UUID.class),r.getObject("suite_version_id",UUID.class),r.getInt("repeat_count"),r.getTimestamp("deadline").toInstant(),r.getString("status"),r.getObject("source_experiment_id",UUID.class),r.getString("agent_name"),r.getString("dataset_name"),r.getInt("dataset_version_number"),r.getString("suite_name"),r.getInt("suite_version_number"),r.getInt("case_count"),r.getInt("task_count"),r.getTimestamp("created_at").toInstant(),r.getObject("sdk_project_id",UUID.class),r.getString("sdk_project"),r.getString("namespace"),r.getString("run_name"),r.getObject("evaluation_authorized",Boolean.class));
    }
}
