package io.agenteval.experiments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.agenteval.agents.AgentService;
import io.agenteval.catalog.*;
import io.agenteval.identity.*;
import io.agenteval.protocol.*;
import io.agenteval.telemetry.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SdkRunService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcClient jdbc;
    private final AgentService agents;
    private final CatalogService catalog;
    private final ExperimentService experiments;
    private final JdbcTelemetryRepository telemetry;
    private final Clock clock;
    private final ProtocolCodec codec = new ProtocolCodec();

    public SdkRunService(JdbcClient jdbc, AgentService agents, CatalogService catalog,
            ExperimentService experiments, JdbcTelemetryRepository telemetry, Clock clock) {
        this.jdbc = jdbc;
        this.agents = agents;
        this.catalog = catalog;
        this.experiments = experiments;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    public record Project(UUID id, String name, boolean autoEvaluate) {}
    public record IssuedProject(Project project, String secret) {}
    public record RunInfo(UUID id, UUID projectId, String project, String namespace, String name,
            String phase, JsonNode estimate) {}
    private record UploadProject(Project project, WorkspaceActor actor) {}
    private record Case(JsonNode task, JsonNode output, TrialExecutionStatus status,
            List<TraceEvent> events, long droppedCount) {}

    public List<Project> projects(WorkspaceActor actor) {
        return jdbc.sql("select id,name,auto_evaluate from sdk_projects where workspace_id=? order by name")
            .param(actor.workspaceId()).query((r,n) -> new Project(r.getObject("id",UUID.class),r.getString("name"),r.getBoolean("auto_evaluate"))).list();
    }

    @Transactional
    public IssuedProject create(WorkspaceActor actor, String name) {
        name = text(name, 100);
        jdbc.sql("select id from workspaces where id=? for update").param(actor.workspaceId()).query(UUID.class).single();
        if (projects(actor).size() >= 100) throw new IllegalArgumentException("Project limit exceeded");
        var project = new Project(UUID.randomUUID(),name,true);
        String secret = newSecret();
        if (jdbc.sql("insert into sdk_projects(id,workspace_id,name,secret_hash) values (?,?,?,?) on conflict(workspace_id,name) do nothing")
                .params(project.id(),actor.workspaceId(),name,hash(secret)).update() != 1) throw new CatalogConflictException();
        return new IssuedProject(project,secret);
    }

    public String rotate(WorkspaceActor actor, UUID project) {
        String secret = newSecret();
        if (jdbc.sql("update sdk_projects set secret_hash=? where id=? and workspace_id=?")
                .params(hash(secret),project,actor.workspaceId()).update() != 1) throw new ScopeViolationException();
        return secret;
    }

    public void configure(WorkspaceActor actor, UUID project, Boolean enabled) {
        if (enabled == null) throw new IllegalArgumentException("auto_evaluate required");
        if (jdbc.sql("update sdk_projects set auto_evaluate=? where id=? and workspace_id=?")
                .params(enabled,project,actor.workspaceId()).update() != 1) throw new ScopeViolationException();
    }

    @Transactional
    public UUID upload(String secret, JsonNode body) {
        if (secret == null || !secret.matches("vey_[A-Za-z0-9_-]{43}")) throw new InvalidUploadKey();
        // Reject invalid credentials before taking the global scheduler lock.
        String digest = hash(secret);
        if (!jdbc.sql("select exists(select 1 from sdk_projects where secret_hash=?)").param(digest).query(Boolean.class).single())
            throw new InvalidUploadKey();

        UUID runId = UUID.fromString(field(body,"run_id",36));
        UUID suite = UUID.fromString(field(body,"suite_version_id",36));
        String namespace = field(body,"namespace",100);
        String name = field(body,"name",200);
        String agentName = field(body,"agent_name",200);
        String agentVersion = field(body,"agent_version",128);
        String datasetVersion = field(body,"dataset_version",128);
        var cases = parseCases(body.path("cases"));
        String fingerprint = CanonicalJson.hash(body);
        var dataset = JsonNodeFactory.instance.objectNode();
        var tasks = dataset.putArray("cases");
        cases.values().forEach(item -> tasks.add(item.task()));
        CatalogService.validateDataset(dataset);

        lockScheduler();
        var upload = jdbc.sql("""
            select p.id,p.name,p.auto_evaluate,p.workspace_id,w.owner_user_id
            from sdk_projects p join workspaces w on w.id=p.workspace_id
            where p.secret_hash=? for update of p
            """).param(digest).query((r,n) -> new UploadProject(
                new Project(r.getObject("id",UUID.class),r.getString("name"),r.getBoolean("auto_evaluate")),
                new WorkspaceActor(r.getObject("owner_user_id",UUID.class),r.getObject("workspace_id",UUID.class),false)))
            .optional().orElseThrow(InvalidUploadKey::new);
        UUID project = upload.project().id();
        var actor = upload.actor();
        var prior = jdbc.sql("select experiment_id,request_hash from sdk_runs where project_id=? and client_run_id=?")
            .params(project,runId).query((r,n) -> Map.entry(r.getObject("experiment_id",UUID.class),r.getString("request_hash"))).optional();
        if (prior.isPresent()) {
            if (!prior.get().getValue().equals(fingerprint)) throw new CatalogConflictException();
            return prior.get().getKey();
        }
        jdbc.sql("select id from workspaces where id=? for update").param(actor.workspaceId()).query(UUID.class).single();
        var version = catalog.version(actor,suite);
        if (version.kind() != ResourceKind.SUITE) throw new IllegalArgumentException("Published rule set required");
        UUID datasetId = resolveDataset(actor,project,namespace,datasetVersion,dataset);
        UUID agent = resolveAgent(actor,project,namespace,agentName,agentVersion);
        var now = clock.instant();
        UUID experiment = experiments.start(actor,new StartExperimentCommand(agent,agentVersion,datasetId,suite,1,
            now.plusSeconds(3600),"sdk:"+project+":"+runId));
        jdbc.sql("insert into sdk_runs(experiment_id,workspace_id,project_id,client_run_id,request_hash,namespace,name,authorized) values (?,?,?,?,?,?,?,?)")
            .params(experiment,actor.workspaceId(),project,runId,fingerprint,namespace,name,upload.project().autoEvaluate()).update();
        var trials = experiments.trials(actor,experiment);
        int index = 0;
        for (var item : cases.values()) {
            var trial = trials.get(index++);
            var events = item.events().stream().map(event -> new TraceEvent(event.schemaVersion(),event.eventId(),
                trial.id(),trial.traceId(),event.spanId(),event.parentSpanId(),event.eventType(),event.spanKind(),
                event.name(),event.sequence(),event.occurredAt(),event.payload(),event.capture())).toList();
            if (!events.isEmpty()) telemetry.persist(actor.workspaceId(),trial.id(),events,now);
            var completion = new CompletionRequest(item.status(),item.output(),
                events.size()+item.droppedCount(),events.size(),item.droppedCount(),List.of());
            var value = encode(completion);
            jdbc.sql("""
                update trials set status=?,started_at=?,completed_at=?,completion=cast(? as jsonb),completion_hash=?
                where id=? and workspace_id=?
                """).params(item.status().name(),Timestamp.from(now),Timestamp.from(now),value.toString(),
                    CanonicalJson.hash(value),trial.id(),actor.workspaceId()).update();
        }
        return experiment;
    }

    public Optional<RunInfo> info(WorkspaceActor actor, UUID id) {
        return jdbc.sql("""
            select r.experiment_id,r.project_id,p.name project,r.namespace,r.name,e.snapshot->'cost_estimate' estimate,
                case when e.status='CANCELLED' then 'CANCELLED'
                     when not r.authorized then 'WAITING'
                     when exists(select 1 from grading_jobs g join trials t on t.id=g.trial_id
                         where t.experiment_id=e.id and g.initial_run) then 'SUBMITTED'
                     else 'PREPARING' end phase
            from sdk_runs r join sdk_projects p on p.id=r.project_id
            join experiments e on e.id=r.experiment_id
            where r.experiment_id=? and r.workspace_id=? and e.status not in ('DELETING','DELETED')
            """).params(id,actor.workspaceId()).query((r,n) -> new RunInfo(r.getObject("experiment_id",UUID.class),
                r.getObject("project_id",UUID.class),r.getString("project"),r.getString("namespace"),r.getString("name"),
                r.getString("phase"),tree(r.getString("estimate")))).optional();
    }

    @Transactional
    public void evaluate(WorkspaceActor actor, UUID id) {
        lockScheduler();
        var run = info(actor,id).orElseThrow(ScopeViolationException::new);
        if (run.phase().equals("CANCELLED")) throw new CatalogConflictException();
        jdbc.sql("update sdk_runs set authorized=true where experiment_id=? and workspace_id=?")
            .params(id,actor.workspaceId()).update();
        JdbcEvidenceRepository.enqueueSdkRunIfReady(jdbc,id,clock.instant());
    }

    private UUID resolveDataset(WorkspaceActor actor, UUID project, String namespace, String version, JsonNode data) {
        String fingerprint = CanonicalJson.hash(data);
        var prior = jdbc.sql("select dataset_version_id,content_hash from sdk_datasets where project_id=? and namespace=? and version=?")
            .params(project,namespace,version).query((r,n) -> Map.entry(r.getObject("dataset_version_id",UUID.class),r.getString("content_hash"))).optional();
        if (prior.isPresent()) {
            if (!prior.get().getValue().equals(fingerprint)) throw new CatalogConflictException();
            return prior.get().getKey();
        }
        var resource = catalog.create(actor,ResourceKind.DATASET,version,data);
        var published = catalog.publish(actor,resource.id());
        catalog.archive(actor,resource.id());
        jdbc.sql("insert into sdk_datasets(project_id,namespace,version,dataset_version_id,content_hash) values (?,?,?,?,?)")
            .params(project,namespace,version,published.id(),fingerprint).update();
        return published.id();
    }

    private UUID resolveAgent(WorkspaceActor actor, UUID project, String namespace, String name, String version) {
        return jdbc.sql("""
            select a.id from sdk_runs r join experiments e on e.id=r.experiment_id join agents a on a.id=e.agent_id
            where r.project_id=? and r.namespace=? and a.name=? and a.version=? limit 1
            """).params(project,namespace,name,version).query(UUID.class).optional()
            .orElseGet(() -> agents.create(actor,name,version).id());
    }

    private SortedMap<String,Case> parseCases(JsonNode items) {
        if (!items.isArray() || items.isEmpty() || items.size()>1000) throw new IllegalArgumentException("Expected 1..1000 cases");
        var cases = new TreeMap<String,Case>();
        int totalEvents = 0;
        for (var item : items) {
            String id = field(item,"case_id",128);
            if (!item.has("input") || !item.has("output")) throw new IllegalArgumentException("Case input and output required");
            var task = JsonNodeFactory.instance.objectNode().put("case_id",id).put("instruction",field(item,"instruction",4096));
            task.set("input",item.get("input"));
            if (item.has("expected")) task.set("expected",item.get("expected"));
            var status = TrialExecutionStatus.valueOf(field(item,"status",24));
            if (!Set.of(TrialExecutionStatus.SUCCEEDED,TrialExecutionStatus.FAILED,TrialExecutionStatus.TIMED_OUT).contains(status))
                throw new IllegalArgumentException("Invalid SDK case status");
            var records = item.path("events");
            if (!records.isArray() || (totalEvents+=records.size())>2000) throw new TelemetryLimitException();
            var events = new ArrayList<TraceEvent>();
            var ids = new HashSet<UUID>();
            for (var raw : records) {
                try {
                    var event = codec.decode(raw.toString(),TraceEvent.class);
                    if (event.sequence()!=events.size()+1 || !ids.add(event.eventId())) throw new IllegalArgumentException("Invalid event sequence");
                    events.add(event);
                } catch (IOException invalid) { throw new IllegalArgumentException("Invalid trace event",invalid); }
            }
            var dropped = item.path("dropped_count");
            if (!dropped.canConvertToInt() || !dropped.isIntegralNumber() || dropped.asInt()<0) throw new IllegalArgumentException("Invalid dropped count");
            if (cases.put(id,new Case(task,item.get("output"),status,List.copyOf(events),dropped.asLong()))!=null)
                throw new IllegalArgumentException("Duplicate case_id");
        }
        return cases;
    }

    private void lockScheduler() { jdbc.sql("select id from scheduler_guard where id=1 for update").query(Integer.class).single(); }
    private static String field(JsonNode body, String name, int limit) {
        var value = body.path(name);
        if (!value.isTextual()) throw new IllegalArgumentException(name+" required");
        return text(value.asText(),limit);
    }
    private static String text(String value, int limit) {
        if (value==null || value.isBlank() || value.length()>limit || value.indexOf(0)>=0) throw new IllegalArgumentException("Invalid text");
        return value.strip();
    }
    private JsonNode tree(String source) { try { return codec.tree(source); } catch (IOException invalid) { throw new IllegalStateException(invalid); } }
    private JsonNode encode(CompletionRequest value) { try { return codec.tree(codec.encode(value)); } catch (IOException invalid) { throw new IllegalStateException(invalid); } }
    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "vey_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static final class InvalidUploadKey extends RuntimeException {}
}
