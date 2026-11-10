package io.agenteval.sdk;
import io.agenteval.protocol.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class HttpWorkerTransport implements WorkerTransport {
    private final HttpClient client;
    private final String base,key,version,instance;
    private final Duration timeout;
    public HttpWorkerTransport(URI base,String key,String version,String instance) {
        this(base,key,version,instance,Duration.ofSeconds(25));
    }
    HttpWorkerTransport(URI base,String key,String version,String instance,Duration timeout) {
        this(base,key,version,instance,timeout,false);
    }
    HttpWorkerTransport(URI base,String key,String version,String instance,Duration timeout,boolean accountSession) {
        var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER);
        if(accountSession) builder.cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER));
        this.client=builder.build();
        if(timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Invalid timeout"); this.timeout=timeout;
        if(base.getHost()==null || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null || !Set.of("https","http").contains(base.getScheme())) throw new IllegalArgumentException("Invalid platform URL");
        if(base.getScheme().equals("http") && !Set.of("localhost","127.0.0.1","[::1]","::1").contains(base.getHost())) throw new IllegalArgumentException("Use HTTPS for remote platforms");
        if((!accountSession && (key==null || key.isBlank())) || version==null || version.isBlank() || instance==null || instance.isBlank()) throw new IllegalArgumentException("Missing worker configuration");
        this.base=base.toString().replaceAll("/+$",""); this.key=key; this.version=version; this.instance=instance;
    }
    UUID uploadRun(com.fasterxml.jackson.databind.JsonNode payload) {
        if(key!=null) return JsonSupport.decode(request("/sdk-runs:upload",payload,null),UploadedRun.class).id();
        var csrf=csrf();
        var response=send(HttpRequest.newBuilder(URI.create(base+"/api/v1/sdk-runs")).timeout(timeout)
            .header(csrf.headerName(),csrf.token()).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build());
        return JsonSupport.decode(checked(response),UploadedRun.class).id();
    }
    void login(String username,String password) {
        var csrf=csrf();
        var form="username="+URLEncoder.encode(username,StandardCharsets.UTF_8)
            +"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8);
        checked(send(HttpRequest.newBuilder(URI.create(base+"/api/v1/auth/login")).timeout(timeout)
            .header(csrf.headerName(),csrf.token()).header("Content-Type","application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build()));
    }
    private Csrf csrf() {
        return JsonSupport.decode(checked(send(HttpRequest.newBuilder(URI.create(base+"/api/v1/auth/csrf"))
            .timeout(timeout).GET().build())),Csrf.class);
    }
    private String checked(HttpResponse<byte[]> response) {
        if(response.statusCode()<200 || response.statusCode()>=300) throw new WorkerRejectedException(response.statusCode());
        return new String(response.body(),StandardCharsets.UTF_8);
    }
    private record Csrf(String headerName,String token) {}
    private record UploadedRun(UUID id) {}
    @Override public UUID register(UUID agent) {
        String identity=instance.substring(0,Math.min(80,instance.length()))+":"+hash(agent.toString()).substring(0,32);
        var request=new RegisterWorkerRequest(agent,version,identity,"1.0","JAVA","0.1.0",1);
        var registration=JsonSupport.decode(request("/workers/register",request,null),WorkerRegistration.class);
        if(registration.heartbeatSeconds()!=15 || registration.leaseSeconds()!=60) throw new WorkerRejectedException(502); return registration.workerId();
    }
    @Override public Optional<LeaseGrant> claim(UUID worker,UUID agent) { var body=request("/workers/"+worker+"/claim",new ClaimRequest(worker,agent,version,20),null); return body==null?Optional.empty():Optional.of(JsonSupport.decode(body,LeaseGrant.class)); }
    @Override public void started(LeaseGrant grant) { request("/trials/"+grant.trialId()+"/started",null,grant); }
    @Override public Heartbeat heartbeat(LeaseGrant grant) { return JsonSupport.decode(request("/trials/"+grant.trialId()+"/heartbeat",null,grant),Heartbeat.class); }
    @Override public List<UUID> uploadEvents(LeaseGrant grant,List<TraceEvent> events) { return JsonSupport.decode(request("/trials/"+grant.trialId()+"/events:batch",Map.of("events",events),grant),EventAck.class).acknowledgedEventIds(); }
    @Override public void complete(LeaseGrant grant,CompletionRequest completion) { request("/trials/"+grant.trialId()+"/complete",completion,grant); }
    @Override public UUID uploadBytes(LeaseGrant lease,byte[] content,String name,String mediaType,String idempotencyKey) {
        String prefix="/trials/"+lease.trialId()+"/artifacts";
        var metadata=new RegisterArtifactRequest(name,mediaType,content.length,hash(content),idempotencyKey);
        var grant=JsonSupport.decode(request(prefix,metadata,lease),ArtifactUploadGrant.class);
        URI uri=grant.uploadUrl(); if(uri.getHost()==null || uri.getUserInfo()!=null || !Set.of("http","https").contains(uri.getScheme())) throw new WorkerRejectedException(502);
        // No platform credentials are attached to the separately signed object-store request.
        var upload=HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type","application/octet-stream").PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build();
        var response=send(upload); if(response.statusCode()<200 || response.statusCode()>=300) throw new WorkerRejectedException(response.statusCode());
        var artifact=JsonSupport.decode(request(prefix+"/"+grant.artifactId()+"/complete",null,lease),ArtifactRef.class);
        if(!artifact.artifactId().equals(grant.artifactId())) throw new WorkerRejectedException(502); return artifact.artifactId();
    }
    @Override public List<String> secrets() { return List.of(key); }
    private String request(String path,Object body,LeaseGrant grant) {
        var builder=HttpRequest.newBuilder(URI.create(base+"/api/v1"+path)).timeout(timeout).header("Authorization","Bearer "+key).header("Content-Type","application/json");
        if(grant!=null) builder.header("X-Attempt-Token",grant.attemptToken());
        builder.POST(body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonSupport.encode(body),StandardCharsets.UTF_8));
        var response=send(builder.build());
        if(response.statusCode()<200 || response.statusCode()>=300) throw new WorkerRejectedException(response.statusCode());
        return response.statusCode()==204?null:new String(response.body(),StandardCharsets.UTF_8);
    }
    private HttpResponse<byte[]> send(HttpRequest request) {
        var future=client.sendAsync(request,info -> new BoundedBodySubscriber(1024*1024));
        try { return future.get(timeout.toNanos(),TimeUnit.NANOSECONDS); }
        catch(InterruptedException stopped) { future.cancel(true); Thread.currentThread().interrupt(); throw new TransportException(); }
        catch(TimeoutException unavailable) { future.cancel(true); throw new TransportException(); }
        catch(ExecutionException unavailable) { future.cancel(true); if(unavailable.getCause() instanceof WorkerRejectedException rejected) throw rejected; throw new TransportException(); }
    }
    private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private String hash(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    @Override public void close() { client.shutdownNow(); }
    public record EventAck(List<UUID> acknowledgedEventIds) { public EventAck { acknowledgedEventIds=List.copyOf(acknowledgedEventIds); } }
}
