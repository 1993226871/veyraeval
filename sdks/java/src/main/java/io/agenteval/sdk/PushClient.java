package io.agenteval.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.agenteval.protocol.TraceEvent;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/** Application-owned runs that upload completed cases without leasing work. */
public final class PushClient implements AutoCloseable {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final HttpWorkerTransport transport;
    private final String key;

    public PushClient(URI platform, String uploadKey) {
        this.transport = new HttpWorkerTransport(platform,uploadKey,"push",UUID.randomUUID().toString());
        this.key = uploadKey;
    }

    private PushClient(HttpWorkerTransport transport) {
        this.transport=transport;
        this.key=null;
    }

    public static PushClient login(URI platform,String username,String password) {
        if(username==null || username.isBlank() || password==null || password.isEmpty())
            throw new IllegalArgumentException("Username and password required");
        var transport=new HttpWorkerTransport(platform,null,"push",UUID.randomUUID().toString(),
            java.time.Duration.ofSeconds(25),true);
        try { transport.login(username,password); return new PushClient(transport); }
        catch(RuntimeException failure) { transport.close(); throw failure; }
    }

    public Run startRun(String project,String namespace,String name,String agentName,String agentVersion,
            String datasetVersion,UUID suiteVersionId) {
        if(project==null || project.isBlank()) throw new IllegalArgumentException("Project name required");
        var run=buildRun(namespace,name,agentName,agentVersion,datasetVersion,suiteVersionId);
        run.payload.put("project",project);
        return run;
    }

    public Run startRun(String namespace, String name, String agentName, String agentVersion,
            String datasetVersion, UUID suiteVersionId) {
        if(key==null) throw new IllegalArgumentException("Project name required");
        return buildRun(namespace,name,agentName,agentVersion,datasetVersion,suiteVersionId);
    }

    private Run buildRun(String namespace,String name,String agentName,String agentVersion,String datasetVersion,UUID suiteVersionId) {
        var payload = JsonNodeFactory.instance.objectNode().put("run_id",UUID.randomUUID().toString())
            .put("namespace",namespace).put("name",name).put("agent_name",agentName).put("agent_version",agentVersion)
            .put("dataset_version",datasetVersion).put("suite_version_id",suiteVersionId.toString());
        return new Run(payload);
    }

    public UUID upload(JsonNode payload) {
        bytes(payload);
        return transport.uploadRun(payload);
    }

    public UUID uploadFile(Path path) throws IOException {
        try(var stream=Files.newInputStream(path)) {
            byte[] value=stream.readNBytes(MAX_BYTES+1);
            if(value.length>MAX_BYTES) throw new IllegalArgumentException("Run exceeds 8 MiB");
            return upload(JsonSupport.tree(new String(value,StandardCharsets.UTF_8)));
        }
    }

    @Override public void close() { transport.close(); }

    private static byte[] bytes(JsonNode payload) {
        byte[] value=payload.toString().getBytes(StandardCharsets.UTF_8);
        if(value.length>MAX_BYTES) throw new IllegalArgumentException("Run exceeds 8 MiB");
        return value;
    }

    public final class Run {
        private final com.fasterxml.jackson.databind.node.ObjectNode payload;
        private final com.fasterxml.jackson.databind.node.ArrayNode cases;
        private final Set<String> ids = new HashSet<>();
        private boolean sealed;
        private boolean active;
        private int eventCount;
        private int eventBytes;

        private Run(com.fasterxml.jackson.databind.node.ObjectNode payload) {
            this.payload=payload;
            this.cases=payload.putArray("cases");
        }

        /** Pass Java null for an omitted reference, or NullNode for an explicit JSON null. */
        public synchronized <T> T runCase(String id, String instruction, Object input, JsonNode expected,
                Supplier<T> agent) {
            if(sealed || active || id==null || id.isBlank() || id.length()>128 || ids.size()>=1000 || ids.contains(id))
                throw new IllegalArgumentException("Invalid, duplicate or sealed case");
            var item=JsonNodeFactory.instance.objectNode().put("case_id",id).put("instruction",instruction);
            item.set("input",JsonSupport.value(input));
            if(expected!=null) item.set("expected",expected.deepCopy());
            item.putNull("output").put("status","FAILED");
            var capture = new Capture();
            var telemetry = new Telemetry(UUID.randomUUID(),UUID.randomUUID().toString().replace("-",""),capture,key==null?List.of():List.of(key));
            ids.add(id);
            active=true;
            try(var context=telemetry.activate(); var root=Telemetry.span("agent",payload.path("agent_name").asText(),input)) {
                try {
                    T output=agent.get();
                    item.set("output",JsonSupport.value(output));
                    item.put("status","SUCCEEDED");
                    root.output(output);
                    return output;
                } catch(RuntimeException | Error failure) {
                    root.failed(failure);
                    throw failure;
                }
            } finally {
                synchronized(capture) {
                    item.set("events",JsonSupport.value(capture.events));
                    item.put("dropped_count",capture.dropped);
                }
                cases.add(item);
                active=false;
            }
        }

        public synchronized void save(Path path) throws IOException {
            seal();
            Files.write(path,bytes(payload),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        }

        public synchronized UUID submit() {
            seal();
            return upload(payload);
        }

        private void seal() {
            if(active || cases.isEmpty()) throw new IllegalStateException("Complete at least one case");
            sealed=true;
        }

        private final class Capture implements Telemetry.Capture {
            private final List<TraceEvent> events = new ArrayList<>();
            private int dropped;
            @Override public String storePayload(UUID trial, byte[] content) { return null; }
            @Override public synchronized boolean append(TraceEvent event) {
                int size=JsonSupport.encode(event).getBytes(StandardCharsets.UTF_8).length;
                if(eventCount>=2000 || eventBytes+size>MAX_BYTES) { dropped++; return false; }
                eventCount++;
                eventBytes+=size;
                events.add(new TraceEvent(event.schemaVersion(),event.eventId(),event.trialId(),event.traceId(),
                    event.spanId(),event.parentSpanId(),event.eventType(),event.spanKind(),event.name(),events.size()+1,
                    event.occurredAt(),event.payload(),event.capture()));
                return true;
            }
            @Override public synchronized void recordDrop(UUID trial) { dropped++; }
        }
    }
}
