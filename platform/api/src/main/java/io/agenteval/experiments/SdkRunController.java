package io.agenteval.experiments;

import com.fasterxml.jackson.databind.JsonNode;
import io.agenteval.protocol.ProtocolCodec;
import io.agenteval.security.CurrentActor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class SdkRunController {
    private final CurrentActor current;
    private final SdkRunService runs;
    private final ProtocolCodec codec = new ProtocolCodec();

    public SdkRunController(CurrentActor current, SdkRunService runs) {
        this.current = current;
        this.runs = runs;
    }

    public record CreateProject(String name) {}
    public record ConfigureProject(Boolean autoEvaluate) {}

    @GetMapping("/sdk-projects")
    public List<SdkRunService.Project> projects(Authentication auth) { return runs.projects(current.require(auth)); }

    @PostMapping("/sdk-projects")
    @ResponseStatus(HttpStatus.CREATED)
    public SdkRunService.IssuedProject create(Authentication auth, @RequestBody CreateProject body) {
        return runs.create(current.require(auth),body.name());
    }

    @PutMapping("/sdk-projects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void configure(Authentication auth, @PathVariable UUID id, @RequestBody ConfigureProject body) {
        runs.configure(current.require(auth),id,body.autoEvaluate());
    }

    @PostMapping("/sdk-projects/{id}/rotate")
    public Map<String,String> rotate(Authentication auth, @PathVariable UUID id) {
        return Map.of("secret",runs.rotate(current.require(auth),id));
    }

    @PostMapping(path="/sdk-runs:upload", consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,UUID> upload(@RequestHeader(value="Authorization",required=false) String auth, @RequestBody byte[] raw) {
        if (auth==null || !auth.startsWith("Bearer ")) throw new SdkRunService.InvalidUploadKey();
        return Map.of("id",runs.upload(auth.substring(7),decode(raw)));
    }

    @PostMapping(path="/sdk-runs", consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,UUID> upload(Authentication auth, @RequestBody byte[] raw) {
        return Map.of("id",runs.upload(current.require(auth),decode(raw)));
    }

    private JsonNode decode(byte[] raw) {
        try { return codec.tree(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString()); }
        catch (IOException invalid) { throw new IllegalArgumentException("Invalid SDK upload",invalid); }
    }

    @GetMapping("/experiments/{id}/sdk-run")
    public ResponseEntity<SdkRunService.RunInfo> info(Authentication auth, @PathVariable UUID id) {
        return ResponseEntity.of(runs.info(current.require(auth),id));
    }

    @PostMapping("/experiments/{id}/sdk-run/evaluate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void evaluate(Authentication auth, @PathVariable UUID id) { runs.evaluate(current.require(auth),id); }

    @ExceptionHandler(SdkRunService.InvalidUploadKey.class)
    public void invalidKey(jakarta.servlet.http.HttpServletResponse response) throws IOException {
        io.agenteval.security.ApiProblems.write(response,401,"INVALID_UPLOAD_KEY");
    }
}
