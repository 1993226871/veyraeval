package io.agenteval.sdk;
import io.agenteval.protocol.TraceEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;

/** Atomic SQLite queue. Only already-redacted telemetry belongs here. */
public final class FileSpool implements AutoCloseable, Telemetry.Capture {
    private final Connection db;
    private final long maxBytes;
    public FileSpool(Path path) { this(path,100L*1024*1024); }
    public FileSpool(Path path,long maxBytes) {
        if(maxBytes<1) throw new IllegalArgumentException("Invalid spool capacity"); this.maxBytes=maxBytes;
        try {
            Files.createDirectories(path.toAbsolutePath().getParent()); db=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());
            if(Files.getFileAttributeView(path,java.nio.file.attribute.PosixFileAttributeView.class)!=null) Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            update("PRAGMA busy_timeout=5000"); update("PRAGMA journal_mode=DELETE"); update("PRAGMA synchronous=FULL");
            update("CREATE TABLE IF NOT EXISTS counters(trial TEXT PRIMARY KEY,sequence INTEGER NOT NULL DEFAULT 0,accepted INTEGER NOT NULL DEFAULT 0,dropped INTEGER NOT NULL DEFAULT 0)");
            update("CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY,trial TEXT NOT NULL,sequence INTEGER NOT NULL,body BLOB NOT NULL)");
            update("CREATE INDEX IF NOT EXISTS events_trial ON events(trial,sequence)");
            update("CREATE TABLE IF NOT EXISTS executions(trial TEXT PRIMARY KEY,token_hash TEXT NOT NULL)");
            update("CREATE TABLE IF NOT EXISTS payloads(id TEXT PRIMARY KEY,trial TEXT NOT NULL,body BLOB NOT NULL)");
            update("CREATE TABLE IF NOT EXISTS payload_links(id TEXT PRIMARY KEY,trial TEXT NOT NULL,artifact_id TEXT NOT NULL)");
        } catch(java.io.IOException | SQLException failure) { throw new IllegalStateException("Cannot open SDK spool",failure); }
    }
    public synchronized boolean append(TraceEvent event) {
        return transaction(() -> {
            String trial=event.trialId().toString(); update("INSERT OR IGNORE INTO counters(trial) VALUES(?)",trial);
            if(scalar("SELECT count(*) FROM events WHERE id=?",event.eventId().toString())!=0) return true;
            long sequence=scalar("SELECT sequence+1 FROM counters WHERE trial=?",trial);
            var next=new TraceEvent(event.schemaVersion(),event.eventId(),event.trialId(),event.traceId(),event.spanId(),event.parentSpanId(),event.eventType(),event.spanKind(),event.name(),sequence,event.occurredAt(),event.payload(),event.capture());
            byte[] body=JsonSupport.encode(next).getBytes(StandardCharsets.UTF_8);
            boolean accepted=usedBytes()+body.length<=maxBytes && sequence<=10000;
            update("UPDATE counters SET sequence=?,accepted=accepted+?,dropped=dropped+? WHERE trial=?",sequence,accepted?1:0,accepted?0:1,trial);
            if(accepted) update("INSERT INTO events(id,trial,sequence,body) VALUES(?,?,?,?)",event.eventId().toString(),trial,sequence,body);
            return accepted;
        });
    }
    public synchronized void recordDrop(UUID trial) { transaction(() -> { update("INSERT OR IGNORE INTO counters(trial) VALUES(?)",trial.toString()); update("UPDATE counters SET sequence=sequence+1,dropped=dropped+1 WHERE trial=?",trial.toString()); return null; }); }
    public synchronized List<TraceEvent> pending(UUID trial) {
        var result=new ArrayList<TraceEvent>(); int size="{\"events\":[]}".getBytes(StandardCharsets.UTF_8).length;
        try(var statement=statement("SELECT body FROM events WHERE trial=? ORDER BY sequence LIMIT 100",trial.toString()); var rows=statement.executeQuery()) {
            while(rows.next()) {
                byte[] bytes=rows.getBytes(1); int added=bytes.length+(result.isEmpty()?0:1); if(size+added>1024*1024) break;
                result.add(JsonSupport.decode(new String(bytes,StandardCharsets.UTF_8),TraceEvent.class)); size+=added;
            }
        } catch(SQLException failure) { throw new IllegalStateException("Cannot read SDK spool",failure); } return List.copyOf(result);
    }
    public synchronized void acknowledge(UUID trial,List<UUID> ids) { transaction(() -> { for(var id:ids) update("DELETE FROM events WHERE trial=? AND id=?",trial.toString(),id.toString()); return null; }); }
    public synchronized Stats stats(UUID trial) {
        try(var statement=statement("SELECT sequence,accepted,dropped FROM counters WHERE trial=?",trial.toString()); var rows=statement.executeQuery()) {
            return rows.next()?new Stats(rows.getLong(1),rows.getLong(2),rows.getLong(3)):new Stats(0,0,0);
        } catch(SQLException failure) { throw new IllegalStateException("Cannot read SDK counters",failure); }
    }
    public synchronized boolean markExecution(UUID trial,String token) { return transaction(() -> update("INSERT OR IGNORE INTO executions(trial,token_hash) VALUES(?,?)",trial.toString(),hash(token))==1); }
    public synchronized String storePayload(UUID trial,byte[] content) {
        String id=hash(trial+":"+new String(content,StandardCharsets.UTF_8));
        return transaction(() -> { if(scalar("SELECT count(*) FROM payloads WHERE id=?",id)!=0) return id; if(usedBytes()+content.length>maxBytes) return null; update("INSERT INTO payloads(id,trial,body) VALUES(?,?,?)",id,trial.toString(),content); return id; });
    }
    public synchronized List<Payload> pendingPayloads(UUID trial) {
        try(var statement=statement("SELECT id,body FROM payloads WHERE trial=? ORDER BY id LIMIT 1",trial.toString()); var rows=statement.executeQuery()) { return rows.next()?List.of(new Payload(rows.getString(1),rows.getBytes(2))):List.of(); }
        catch(SQLException failure) { throw new IllegalStateException("Cannot read payload spool",failure); }
    }
    public synchronized void acknowledgePayload(UUID trial,String id,UUID artifact) { transaction(() -> { update("INSERT OR IGNORE INTO payload_links(id,trial,artifact_id) VALUES(?,?,?)",id,trial.toString(),artifact.toString()); update("DELETE FROM payloads WHERE trial=? AND id=?",trial.toString(),id); return null; }); }
    public synchronized List<UUID> artifactIds(UUID trial) {
        var result=new ArrayList<UUID>();
        try(var statement=statement("SELECT artifact_id FROM payload_links WHERE trial=? ORDER BY id",trial.toString()); var rows=statement.executeQuery()) { while(rows.next()) result.add(UUID.fromString(rows.getString(1))); }
        catch(SQLException failure) { throw new IllegalStateException("Cannot read payload links",failure); } return List.copyOf(result);
    }
    private long usedBytes() { return scalar("SELECT (SELECT coalesce(sum(length(body)),0) FROM events)+(SELECT coalesce(sum(length(body)),0) FROM payloads)"); }
    private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private <T> T transaction(Supplier<T> action) {
        update("BEGIN IMMEDIATE");
        try { T result=action.get(); update("COMMIT"); return result; }
        catch(RuntimeException | Error failure) { update("ROLLBACK"); throw failure; }
    }
    private PreparedStatement statement(String sql,Object... values) throws SQLException {
        var statement=db.prepareStatement(sql); for(int i=0;i<values.length;i++) { if(values[i] instanceof byte[] bytes) statement.setBytes(i+1,bytes); else statement.setObject(i+1,values[i]); } return statement;
    }
    private int update(String sql,Object... values) { try(var statement=statement(sql,values)) { statement.execute(); return statement.getUpdateCount(); } catch(SQLException failure) { throw new IllegalStateException("SDK spool update failed",failure); } }
    private long scalar(String sql,Object... values) { try(var statement=statement(sql,values); var rows=statement.executeQuery()) { return rows.next()?rows.getLong(1):0; } catch(SQLException failure) { throw new IllegalStateException("SDK spool query failed",failure); } }
    @Override public synchronized void close() { try { db.close(); } catch(SQLException failure) { throw new IllegalStateException("Cannot close SDK spool",failure); } }
    public record Stats(long finalSequence,long eventCount,long droppedCount) {}
    public record Payload(String id,byte[] content) { public Payload { content=content.clone(); } @Override public byte[] content() { return content.clone(); } }
}
