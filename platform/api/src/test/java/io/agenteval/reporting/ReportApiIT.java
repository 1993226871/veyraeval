package io.agenteval.reporting;
import io.agenteval.api.PlatformApiApplication;
import io.agenteval.identity.*;
import io.agenteval.agents.AgentService;
import io.agenteval.catalog.TemplateService;
import io.agenteval.experiments.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=PlatformApiApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.servlet.session.cookie.secure=false","app.secure-cookies=false"})
class ReportApiIT {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.11");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) { p.add("spring.datasource.url",postgres::getJdbcUrl); p.add("spring.datasource.username",postgres::getUsername); p.add("spring.datasource.password",postgres::getPassword); }
    @Autowired IdentityService identities;
    @Autowired AgentService agents;
    @Autowired TemplateService templates;
    @Autowired ExperimentService experiments;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired io.agenteval.telemetry.EvidenceService evidence;
    @Autowired io.agenteval.catalog.CatalogService catalog;
    @LocalServerPort int port;
    @Test void authenticatesReportsAndHidesOtherWorkspacesAndTaskReferences() throws Exception {
        var account=identities.createUser(UUID.randomUUID()+"@test.local","Temporary-Password-1",false); var actor=account.actor();
        var agent=agents.create(actor,"orders","v1"); var bundle=templates.copyStarter(actor);
        UUID experiment=experiments.start(actor,new StartExperimentCommand(agent.id(),"v1",bundle.datasetVersion().id(),bundle.suiteVersion().id(),1,Instant.now().plusSeconds(300),UUID.randomUUID().toString()));
        try(var anonymous=HttpClient.newHttpClient(); var owner=login(account.email()); var other=login(identities.createUser(UUID.randomUUID()+"@test.local","Temporary-Password-1",false).email())) {
            String path="/api/v1/reports/experiments/"+experiment;
            assertThat(get(anonymous,path).statusCode()).isEqualTo(401);
            var response=get(owner,path); assertThat(response.statusCode()).isEqualTo(200);
            var body=json.readTree(response.body()); assertThat(body.path("planned_attempts").asInt()).isEqualTo(1); assertThat(body.path("determinable_attempts").asInt()).isZero();
            assertThat(get(other,path).statusCode()).isEqualTo(404);
            var datasetDraft=catalog.draft(actor,bundle.datasetVersion().resourceId());
            var invalidImport=post(owner,"/api/v1/catalog/"+datasetDraft.id()+"/import",json.writeValueAsString(Map.of("revision",datasetDraft.revision(),"jsonl","{\"instruction\":\"valid\",\"input\":{}}\ninvalid")));
            assertThat(invalidImport.statusCode()).isEqualTo(400); assertThat(json.readTree(invalidImport.body()).path("line").asInt()).isEqualTo(2);
            assertThat(catalog.draft(actor,datasetDraft.id()).revision()).isEqualTo(datasetDraft.revision());
            var page=get(owner,path+"/trials?limit=1"); assertThat(page.statusCode()).isEqualTo(200); assertThat(json.readTree(page.body()).path("items")).hasSize(1);
            assertThat(page.body()).doesNotContain("instruction","expected","order_id");
            var trial=experiments.trials(actor,experiment).getFirst();
            jdbc.sql("update trials set status='SUCCEEDED',completed_at=?,completion=cast(? as jsonb) where id=?").params(java.sql.Timestamp.from(Instant.now().minusSeconds(31)),"{\"status\":\"SUCCEEDED\",\"output\":{\"amount\":100},\"final_sequence\":0,\"event_count\":0,\"dropped_count\":0,\"artifact_ids\":[]}",trial.id()).update();
            evidence.freeze(actor,trial.id(),false);
            var grading=new io.agenteval.evaluation.EvaluationOrchestrator(new io.agenteval.evaluation.JdbcEvaluationRepository(jdbc,transactions,json),evidence,new io.agenteval.evaluation.RuleEvaluator(),java.time.Clock.systemUTC());
            grading.schedule(100); assertThat(grading.runNext()).isTrue();
            UUID result=jdbc.sql("select job_id from evaluation_results where workspace_id=?").param(actor.workspaceId()).query(UUID.class).single();
            String original=jdbc.sql("select payload::text from evaluation_results where job_id=?").param(result).query(String.class).single();
            String reviews="/api/v1/results/"+result+"/reviews";
            assertThat(post(owner,reviews,"{\"score\":0,\"reason\":\"Manual calibration disagreement\"}").statusCode()).isEqualTo(200);
            assertThat(json.readTree(get(owner,reviews).body())).hasSize(1);
            assertThat(get(other,reviews).statusCode()).isEqualTo(404);
            assertThat(post(other,reviews,"{\"score\":1,\"reason\":\"Foreign\"}").statusCode()).isEqualTo(404);
            assertThat(jdbc.sql("select payload::text from evaluation_results where job_id=?").param(result).query(String.class).single()).isEqualTo(original);
            String comparison="/api/v1/reports/comparison?left="+experiment+"&right="+experiment;
            assertThat(get(owner,comparison).statusCode()).isEqualTo(200);
            assertThat(json.readTree(get(owner,comparison).body()).path("directly_comparable").asBoolean()).isTrue();
            assertThat(get(other,comparison).statusCode()).isEqualTo(404);
            var exported=get(owner,path+"/export");
            assertThat(exported.statusCode()).isEqualTo(200);
            assertThat(json.readTree(exported.body()).path("reviews")).hasSize(1);
            assertThat(json.readTree(exported.body()).path("evidence")).hasSize(1);
            assertThat(exported.body()).doesNotContain("api_key","credential_hash","experiment_snapshot");
            assertThat(get(other,path+"/export").statusCode()).isEqualTo(404);
            String regradePath="/api/v1/trial-evaluations/"+trial.id()+"/reevaluations";
            String command=json.writeValueAsString(Map.of("suite_version_id",bundle.suiteVersion().id(),"include_late",false,"idempotency_key","http-regrade"));
            var regrade=post(owner,regradePath,command); assertThat(regrade.statusCode()).isEqualTo(202);
            var replay=post(owner,regradePath,command); assertThat(replay.statusCode()).isEqualTo(202); assertThat(replay.body()).isEqualTo(regrade.body());
            String run=json.readTree(regrade.body()).path("run_id").asText();
            assertThat(get(owner,"/api/v1/evaluation-runs/"+run).statusCode()).isEqualTo(200);
            assertThat(get(other,"/api/v1/evaluation-runs/"+run).statusCode()).isEqualTo(404);
            assertThat(post(other,regradePath,command).statusCode()).isEqualTo(404);
            var draft=catalog.draft(actor,bundle.evaluatorVersion().resourceId());
            String previewPath="/api/v1/evaluators/"+draft.id()+"/previews";
            var preview=post(owner,previewPath,json.writeValueAsString(Map.of("revision",draft.revision(),"trial_id",trial.id(),"idempotency_key","http-preview")));
            assertThat(preview.statusCode()).isEqualTo(202);
            String previewRun=json.readTree(preview.body()).path("run_id").asText();
            assertThat(json.readTree(get(owner,"/api/v1/evaluation-runs/"+previewRun).body()).path("purpose").asText()).isEqualTo("PREVIEW");
        }
    }
    @Test void sdkRunUploadIsScopedAndQueuesOnlyAuthorizedCompleteRuns() throws Exception {
        var account=identities.createUser(UUID.randomUUID()+"@test.local","Temporary-Password-1",false);
        var actor=account.actor();
        var starter=templates.copyStarter(actor);
        try(var owner=login(account.email()); var anonymous=HttpClient.newHttpClient();
                var other=login(identities.createUser(UUID.randomUUID()+"@test.local","Temporary-Password-1",false).email())) {
            var issued=post(owner,"/api/v1/sdk-projects","{\"name\":\"Orders\"}");
            assertThat(issued.statusCode()).isEqualTo(201);
            var project=json.readTree(issued.body());
            assertThat(project.at("/project/auto_evaluate").asBoolean()).isTrue();
            String secret=project.path("secret").asText();
            String projectPath="/api/v1/sdk-projects/"+project.at("/project/id").asText();
            assertThat(get(owner,"/api/v1/sdk-projects").body()).doesNotContain(secret);
            var csrf=json.readTree(get(owner,"/api/v1/auth/csrf").body());
            var configure=HttpRequest.newBuilder(uri(projectPath)).header("Content-Type","application/json")
                .header(csrf.path("header_name").asText(),csrf.path("token").asText())
                .PUT(HttpRequest.BodyPublishers.ofString("{\"auto_evaluate\":false}")).build();
            assertThat(owner.send(configure,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204);

            var upload=json.createObjectNode().put("run_id",UUID.randomUUID().toString()).put("namespace","regression")
                .put("name","Order agent v2").put("agent_name","order-agent").put("agent_version","v2")
                .put("dataset_version","orders-v1").put("suite_version_id",starter.suiteVersion().id().toString());
            var cases=upload.putArray("cases");
            for(String id:List.of("a","b")) {
                var task=cases.addObject().put("case_id",id).put("instruction","Lookup order")
                    .put("status","SUCCEEDED").put("dropped_count",0);
                task.putObject("input").put("order_id",id);
                task.putObject("expected").put("amount",100);
                task.putObject("output").put("amount",100);
                task.putArray("events");
            }
            assertThat(upload(anonymous,"invalid",upload).statusCode()).isEqualTo(401);
            var accepted=upload(anonymous,secret,upload);
            assertThat(accepted.statusCode()).isEqualTo(202);
            assertThat(upload(anonymous,secret,upload).body()).isEqualTo(accepted.body());
            String id=json.readTree(accepted.body()).path("id").asText();
            String runPath="/api/v1/experiments/"+id+"/sdk-run";
            assertThat(get(other,runPath).statusCode()).isEqualTo(404);
            assertThat(post(other,runPath+"/evaluate","{}").statusCode()).isEqualTo(404);
            assertThat(json.readTree(get(owner,runPath).body()).path("phase").asText()).isEqualTo("WAITING");
            var repository=new io.agenteval.telemetry.JdbcEvidenceRepository(jdbc,transactions,json);
            var trials=experiments.trials(actor,UUID.fromString(id));
            var future=Instant.now().plusSeconds(35);
            repository.freeze(actor,trials.getFirst().id(),false,future);
            repository.freeze(actor,trials.getLast().id(),false,future);
            assertThat(sdkJobCount(id)).isZero();
            var report=json.readTree(get(owner,"/api/v1/reports/experiments/"+id+"/trials").body());
            assertThat(report.at("/items/0/snapshot_id").asText()).isNotBlank();
            assertThat(post(owner,runPath+"/evaluate","{}").statusCode()).isEqualTo(202);
            assertThat(post(owner,runPath+"/evaluate","{}").statusCode()).isEqualTo(202);
            assertThat(sdkJobCount(id)).isEqualTo(2);

            csrf=json.readTree(get(owner,"/api/v1/auth/csrf").body());
            configure=HttpRequest.newBuilder(uri(projectPath)).header("Content-Type","application/json")
                .header(csrf.path("header_name").asText(),csrf.path("token").asText())
                .PUT(HttpRequest.BodyPublishers.ofString("{\"auto_evaluate\":true}")).build();
            assertThat(owner.send(configure,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204);
            upload.put("run_id",UUID.randomUUID().toString()).put("name","Order agent v3").put("agent_version","v3");
            var auto=upload(anonymous,secret,upload);
            assertThat(auto.statusCode()).isEqualTo(202);
            String autoId=json.readTree(auto.body()).path("id").asText();
            var autoTrials=experiments.trials(actor,UUID.fromString(autoId));
            repository.freeze(actor,autoTrials.getFirst().id(),false,future);
            assertThat(sdkJobCount(autoId)).isZero();
            repository.freeze(actor,autoTrials.getLast().id(),false,future);
            assertThat(sdkJobCount(autoId)).isEqualTo(2);
            var grading=new io.agenteval.evaluation.EvaluationOrchestrator(
                new io.agenteval.evaluation.JdbcEvaluationRepository(jdbc,transactions,json),evidence,
                new io.agenteval.evaluation.RuleEvaluator(),java.time.Clock.offset(java.time.Clock.systemUTC(),java.time.Duration.ofSeconds(60)));
            grading.schedule(100);
            for(int i=0;i<20 && grading.runNext();i++) {}
            var autoReport=json.readTree(get(owner,"/api/v1/reports/experiments/"+autoId).body());
            assertThat(autoReport.path("pending_attempts").asInt()).isZero();
            assertThat(autoReport.path("finished_attempts").asInt()).isEqualTo(2);
        }
    }

    private int sdkJobCount(String experiment) {
        return jdbc.sql("select count(*) from grading_jobs g join trials t on t.id=g.trial_id where t.experiment_id=?")
            .param(UUID.fromString(experiment)).query(Integer.class).single();
    }

    private HttpResponse<String> upload(HttpClient client,String key,com.fasterxml.jackson.databind.JsonNode body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/api/v1/sdk-runs:upload"))
            .header("Authorization","Bearer "+key).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString());
    }
    URI uri(String path) { return URI.create("http://localhost:"+port+path); }
    HttpResponse<String> get(HttpClient client,String path) throws Exception { return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
    HttpResponse<String> post(HttpClient client,String path,String body) throws Exception {
        var csrf=json.readTree(get(client,"/api/v1/auth/csrf").body());
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type","application/json").header(csrf.path("header_name").asText(),csrf.path("token").asText()).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpClient login(String email) throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var csrf=json.readTree(get(client,"/api/v1/auth/csrf").body());
        var request=HttpRequest.newBuilder(uri("/api/v1/auth/login")).header("Content-Type","application/x-www-form-urlencoded").header(csrf.path("header_name").asText(),csrf.path("token").asText())
            .POST(HttpRequest.BodyPublishers.ofString("username="+URLEncoder.encode(email,java.nio.charset.StandardCharsets.UTF_8)+"&password=Temporary-Password-1")).build();
        assertThat(client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        csrf=json.readTree(get(client,"/api/v1/auth/csrf").body());
        request=HttpRequest.newBuilder(uri("/api/v1/auth/password")).header("Content-Type","application/json").header(csrf.path("header_name").asText(),csrf.path("token").asText())
            .POST(HttpRequest.BodyPublishers.ofString("{\"current_password\":\"Temporary-Password-1\",\"new_password\":\"Updated-Password-22\"}")).build();
        assertThat(client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204); return client;
    }
}
