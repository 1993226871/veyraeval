package io.agenteval.sdk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.agenteval.protocol.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class Telemetry {
    private static final ThreadLocal<Context> CURRENT=new ThreadLocal<>();
    private static final Pattern PRIVATE=Pattern.compile("authorization|password|secret|token|api[_-]?key|cookie",Pattern.CASE_INSENSITIVE);
    private final UUID trial;
    private final String trace;
    private final Capture spool;
    private final List<String> secrets;
    public Telemetry(UUID trial,String trace,FileSpool spool,List<String> secrets) { this(trial,trace,(Capture)spool,secrets); }
    Telemetry(UUID trial,String trace,Capture spool,List<String> secrets) { this.trial=trial; this.trace=trace; this.spool=spool; this.secrets=secrets.stream().filter(s -> s!=null && !s.isEmpty()).toList(); }
    public Scope activate() { return install(new Context(this,null)); }
    public static Context captureContext() { return CURRENT.get(); }
    public static <T> T runWithContext(Context context,Supplier<T> action) { try(var scope=install(context)) { return action.get(); } }
    public static void runWithContext(Context context,Runnable action) { try(var scope=install(context)) { action.run(); } }
    static Scope install(Context context) { var old=CURRENT.get(); if(context==null) CURRENT.remove(); else CURRENT.set(context); return new Scope(old); }
    public static SpanScope span(String kind,String name) { return span(kind,name,null); }
    public static SpanScope span(String kind,String name,Object input) { return new SpanScope(SpanKind.parse(kind),name,input,CURRENT.get()); }
    public static <T> T tool(String name,Supplier<T> action) {
        try(var scope=span("tool",name)) {
            try { T output=action.get(); scope.output(output); return output; }
            catch(RuntimeException | Error failure) { scope.failed(failure); throw failure; }
        }
    }
    void emit(EventType type,SpanKind kind,String name,String span,String parent,Object value) {
        try {
            var state=new CaptureState(); var payload=clean(value,state,0); String label=mask(name,state);
            byte[] encoded=payload.toString().getBytes(StandardCharsets.UTF_8);
            if(encoded.length>65536) { String reference=spool.storePayload(trial,encoded); payload=reference==null?JsonSupport.tree("{\"omitted\":\"spool capacity exceeded\"}"):JsonNodeFactory.instance.objectNode().put("payload_ref",reference); state.truncated=true; }
            spool.append(new TraceEvent("1.0",UUID.randomUUID(),trial,trace,span,parent,type,kind,label,1,Instant.now(),payload,new CaptureMetadata(state.redacted,state.truncated)));
        } catch(Exception captureFailure) { try { spool.recordDrop(trial); } catch(Exception ignored) {} }
    }
    private JsonNode clean(Object value,CaptureState state,int depth) {
        var factory=JsonNodeFactory.instance;
        if(depth>16) { state.truncated=true; return factory.textNode("[depth limit]"); }
        if(value==null) return NullNode.instance;
        if(value instanceof JsonNode node) {
            if(node.isObject()) { var result=factory.objectNode(); int index=0; for(var fields=node.fields();fields.hasNext();) { var field=fields.next(); if(index++>=128) { state.truncated=true; break; } put(result,field.getKey(),field.getValue(),state,depth); } return result; }
            if(node.isArray()) { var result=factory.arrayNode(); for(int i=0;i<Math.min(128,node.size());i++) result.add(clean(node.get(i),state,depth+1)); if(node.size()>128) state.truncated=true; return result; }
            if(node.isTextual()) return factory.textNode(mask(node.asText(),state));
            return node.deepCopy();
        }
        if(value instanceof Map<?,?> map) { var result=factory.objectNode(); int index=0; for(var entry:map.entrySet()) { if(index++>=128) { state.truncated=true; break; } put(result,String.valueOf(entry.getKey()),entry.getValue(),state,depth); } return result; }
        if(value instanceof Iterable<?> values) { var result=factory.arrayNode(); int index=0; for(var item:values) { if(index++>=128) { state.truncated=true; break; } result.add(clean(item,state,depth+1)); } return result; }
        if(value instanceof String text) return factory.textNode(mask(text,state));
        if(value instanceof Number || value instanceof Boolean) return JsonSupport.value(value);
        state.truncated=true; return factory.objectNode().put("unsupported_type",value.getClass().getSimpleName());
    }
    private void put(ObjectNode result,String key,Object value,CaptureState state,int depth) { String masked=mask(key,state); if(PRIVATE.matcher(masked).find()) { state.redacted=true; result.put(masked,"[redacted]"); } else result.set(masked,clean(value,state,depth+1)); }
    private String mask(String value,CaptureState state) { for(var secret:secrets) if(value.contains(secret)) { value=value.replace(secret,"[redacted]"); state.redacted=true; } if(value.length()>1024*1024) { value=value.substring(0,1024*1024); state.truncated=true; } return value; }
    interface Capture {
        String storePayload(UUID trial,byte[] content);
        boolean append(TraceEvent event);
        void recordDrop(UUID trial);
    }
    public record Context(Telemetry telemetry,String parentSpanId) {}
    private static final class CaptureState { boolean redacted,truncated; }
    public static final class Scope implements AutoCloseable {
        private final Context previous;
        private boolean closed;
        private Scope(Context previous) { this.previous=previous; }
        @Override public void close() { if(!closed) { if(previous==null) CURRENT.remove(); else CURRENT.set(previous); closed=true; } }
    }
}
