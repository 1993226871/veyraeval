package io.agenteval.sdk;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import io.agenteval.protocol.*;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
class HttpTransportTest {
    @Test void artifactUploadOmitsPlatformBearerAndAttemptHeaders() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); var id=UUID.randomUUID(); var uploaded=new AtomicReference<byte[]>(); var headers=new AtomicReference<com.sun.net.httpserver.Headers>(); var codec=new ProtocolCodec();
        var uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        server.createContext("/api/v1",exchange -> {
            var input=exchange.getRequestBody().readAllBytes(); Object response;
            if(exchange.getRequestURI().getPath().endsWith("/artifacts")) {
                var metadata=codec.decode(new String(input,java.nio.charset.StandardCharsets.UTF_8),RegisterArtifactRequest.class);
                response=new ArtifactUploadGrant(id,uri.resolve("/upload"),Instant.now().plusSeconds(120),"application/octet-stream",metadata.sizeBytes());
            } else response=new ArtifactRef(id,"report.txt","text/plain",3,"a".repeat(64));
            byte[] bytes=codec.encode(response).getBytes(java.nio.charset.StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/upload",exchange -> { headers.set(exchange.getRequestHeaders()); uploaded.set(exchange.getRequestBody().readAllBytes()); exchange.sendResponseHeaders(200,-1); exchange.close(); }); server.start();
        try(var transport=new HttpWorkerTransport(uri,"private-bearer","v1","instance")) {
            assertThat(transport.uploadBytes(WorkerContractTest.grant(),"abc".getBytes(),"report.txt","text/plain","request-one")).isEqualTo(id);
            assertThat(new String(uploaded.get())).isEqualTo("abc");
            assertThat(headers.get().getFirst("Authorization")).isNull(); assertThat(headers.get().getFirst("X-Attempt-Token")).isNull();
            assertThat(headers.get().getFirst("Content-Type")).isEqualTo("application/octet-stream");
        } finally { server.stop(0); }
    }
    @Test void pushesCapturedCasesAndReplaysTheSameRun() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var report=UUID.randomUUID();
        var bodies=new ConcurrentLinkedQueue<com.fasterxml.jackson.databind.JsonNode>();
        server.createContext("/api/v1/sdk-runs:upload",exchange -> {
            bodies.add(JsonSupport.tree(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
            byte[] bytes=JsonSupport.encode(Map.of("id",report)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202,bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try(var client=new PushClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fixture-key")) {
            var run=client.startRun("regression","Order v2","orders","v2","orders-v1",UUID.randomUUID());
            run.runCase("A","Query order",Map.of("id","A"),JsonSupport.value(Map.of("amount",100)),
                () -> Telemetry.tool("query_order",() -> Map.of("amount",100)));
            assertThat(run.submit()).isEqualTo(report);
            assertThat(run.submit()).isEqualTo(report);
            var first=bodies.remove();
            assertThat(bodies.remove()).isEqualTo(first);
            assertThat(first.at("/cases/0/events")).hasSize(4);
            assertThat(first.at("/cases/0/events/1/span_kind").asText()).isEqualTo("tool");
            assertThat(first.at("/cases/0/output/amount").asInt()).isEqualTo(100);
            assertThat(first.toString()).doesNotContain("fixture-key");
        } finally { server.stop(0); }
    }
    @Test void limitsResponseBodyBeforeFullyBufferingIt() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1",exchange -> { exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200,1024*1024+1); try { exchange.getResponseBody().write(new byte[1024*1024+1]); } finally { exchange.close(); } }); server.start();
        try(var transport=new HttpWorkerTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"private-bearer","v1","instance")) { assertThatThrownBy(() -> transport.started(WorkerContractTest.grant())).isInstanceOf(WorkerRejectedException.class); }
        finally { server.stop(0); }
    }
    @Test void responseBodyStallHasAnOverallDeadlineAndErrorsDoNotExposeSecrets() throws Exception {
        var release=new CountDownLatch(1); var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1",exchange -> { exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200,100); try { release.await(3,TimeUnit.SECONDS); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); } exchange.close(); }); server.start();
        try(var transport=new HttpWorkerTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"private-bearer","v1","instance",Duration.ofMillis(100))) {
            long begin=System.nanoTime();
            assertThatThrownBy(() -> transport.started(WorkerContractTest.grant())).isInstanceOf(TransportException.class).hasMessageNotContaining("private-bearer").hasMessageNotContaining("sensitive-attempt");
            assertThat(Duration.ofNanos(System.nanoTime()-begin)).isLessThan(Duration.ofSeconds(2));
        } finally { release.countDown(); server.stop(0); }
    }
}
