package io.agenteval.catalog;
import io.agenteval.security.CurrentActor;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final CatalogService catalog;
    private final DatasetService datasets;
    private final TemplateService templates;
    private final CurrentActor current;
    private final io.agenteval.evaluation.JudgeTemplateService judges;
    public CatalogController(CatalogService catalog,DatasetService datasets,TemplateService templates,CurrentActor current,io.agenteval.evaluation.JudgeTemplateService judges) { this.catalog=catalog; this.datasets=datasets; this.templates=templates; this.current=current; this.judges=judges; }
    public record CreateRequest(ResourceKind kind,String name,JsonNode content) {}
    public record UpdateRequest(long revision,String name,JsonNode content) {}
    public record ImportRequest(long revision,String jsonl) {}
    @GetMapping("/catalog") public List<CatalogResource> list(Authentication auth,@RequestParam ResourceKind kind,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="25") int limit) { return catalog.list(current.require(auth),kind,offset,limit); }
    @PostMapping("/catalog") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public CatalogResource create(Authentication auth,@RequestBody CreateRequest request) { return catalog.create(current.require(auth),request.kind(),request.name(),request.content()); }
    @GetMapping("/catalog/{id}") public CatalogResource get(Authentication auth,@PathVariable UUID id) { return catalog.draft(current.require(auth),id); }
    @PutMapping("/catalog/{id}") public CatalogResource update(Authentication auth,@PathVariable UUID id,@RequestBody UpdateRequest request) { return catalog.update(current.require(auth),id,request.revision(),request.name(),request.content()); }
    @DeleteMapping("/catalog/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void archive(Authentication auth,@PathVariable UUID id) { catalog.archive(current.require(auth),id); }
    @PostMapping("/catalog/{id}/versions") public PublishedVersion publish(Authentication auth,@PathVariable UUID id) { return catalog.publish(current.require(auth),id); }
    @GetMapping("/catalog/versions/{id}") public PublishedVersion version(Authentication auth,@PathVariable UUID id) { return catalog.version(current.require(auth),id); }
    @GetMapping("/catalog/{id}/versions") public List<PublishedVersionSummary> versions(Authentication auth,@PathVariable UUID id,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="25") int limit) { return catalog.versions(current.require(auth),id,offset,limit); }
    @PostMapping("/catalog/{id}/import") public CatalogResource importJsonl(Authentication auth,@PathVariable UUID id,@RequestBody ImportRequest request) { return datasets.importJsonl(current.require(auth),id,request.revision(),request.jsonl()); }
    public record JudgeStarter(UUID modelProfileId,UUID priceVersionId) {}
    @PostMapping("/templates/starter/with-judge/copy") @org.springframework.transaction.annotation.Transactional
    public TemplateService.StarterBundle judgeStarter(Authentication auth,@RequestBody JudgeStarter request) {
        var actor=current.require(auth);
        var draft=judges.copy(actor,"evidence-support",request.modelProfileId(),request.priceVersionId());
        var judge=catalog.publish(actor,draft.id());
        return templates.copyStarter(actor,judge.id());
    }
    @PostMapping("/templates/starter-rules/copy") @org.springframework.transaction.annotation.Transactional
    public PublishedVersion starterRules(Authentication auth,@RequestBody(required=false) JudgeStarter request) {
        var actor=current.require(auth);
        if(request==null) return templates.copyStarterRules(actor,null);
        var draft=judges.copy(actor,"evidence-support",request.modelProfileId(),request.priceVersionId());
        return templates.copyStarterRules(actor,catalog.publish(actor,draft.id()).id());
    }
    @GetMapping("/templates") public List<TemplateService.Template> templates() { return templates.list(); }
    @PostMapping("/templates/{id}/copy") public CatalogResource copy(Authentication auth,@PathVariable String id) { return templates.copy(current.require(auth),id); }
    @PostMapping("/templates/starter/copy") @org.springframework.transaction.annotation.Transactional
    public TemplateService.StarterBundle starter(Authentication auth) { return templates.copyStarter(current.require(auth)); }
}
