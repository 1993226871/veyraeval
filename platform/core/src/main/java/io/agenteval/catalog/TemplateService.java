package io.agenteval.catalog;
import com.fasterxml.jackson.databind.JsonNode;
import io.agenteval.identity.WorkspaceActor;
import java.util.List;

public final class TemplateService {
    public record Template(String id,ResourceKind kind,String name,JsonNode content) {
        public Template { content=content.deepCopy(); }
        @Override public JsonNode content() { return content.deepCopy(); }
    }
    private final CatalogService catalog;
    private final List<Template> templates;
    public TemplateService(CatalogService catalog,List<Template> templates) { this.catalog=catalog; this.templates=List.copyOf(templates); }
    public List<Template> list() { return templates; }
    public record StarterBundle(PublishedVersion datasetVersion,PublishedVersion evaluatorVersion,PublishedVersion suiteVersion) {}
    public StarterBundle copyStarter(WorkspaceActor actor) { return copyStarter(actor,null); }
    public StarterBundle copyStarter(WorkspaceActor actor,java.util.UUID judgeVersionId) {
        var dataset=copy(actor,"order-query");
        var evaluator=copy(actor,"amount-rule");
        var evaluatorVersion=catalog.publish(actor,evaluator.id());
        var suite=publishStarterRules(actor,evaluatorVersion.id(),judgeVersionId);
        return new StarterBundle(catalog.publish(actor,dataset.id()),evaluatorVersion,suite);
    }
    public PublishedVersion copyStarterRules(WorkspaceActor actor,java.util.UUID judgeVersionId) {
        var definition=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
            .put("type","RULE").put("criterion_id","answer_exact").put("threshold",1);
        definition.putArray("required_evidence").add("output").add("expected");
        definition.putObject("config").put("operator","eq").put("selector","/output").put("expected_selector","/expected");
        var content=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        var entries=content.putArray("entries");
        entries.addObject().put("name","答案一致性").put("dimension","outcome").put("required_to_pass",true)
            .put("weight",1).set("definition",definition);
        if(judgeVersionId!=null) {
            var judge=catalog.version(actor,judgeVersionId);
            if(judge.kind()!=ResourceKind.EVALUATOR || !judge.content().path("type").asText().equals("LLM"))
                throw new IllegalArgumentException("LLM Judge version required");
            entries.addObject().put("name",judge.name()).put("dimension","outcome").put("required_to_pass",true)
                .put("weight",1).set("definition",judge.content());
        }
        var suite=catalog.create(actor,ResourceKind.SUITE,judgeVersionId==null?"订单规则评分":"订单综合评分",content);
        return catalog.publish(actor,suite.id());
    }
    private PublishedVersion publishStarterRules(WorkspaceActor actor,java.util.UUID evaluatorVersion,java.util.UUID judgeVersionId) {
        var judge=judgeVersionId==null?null:catalog.version(actor,judgeVersionId);
        if(judge!=null && (judge.kind()!=ResourceKind.EVALUATOR || !judge.content().path("type").asText().equals("LLM"))) throw new IllegalArgumentException("LLM Judge version required");
        var item=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        item.put("evaluator_version",evaluatorVersion.toString()); item.put("dimension","outcome"); item.put("required_to_pass",true); item.put("weight",1);
        var content=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(); content.putArray("entries").add(item);
        if(judge!=null) ((com.fasterxml.jackson.databind.node.ArrayNode)content.path("entries")).add(item.deepCopy().put("evaluator_version",judge.id().toString()));
        var suite=catalog.create(actor,ResourceKind.SUITE,judge==null?"订单规则评分":"订单综合评分",content);
        return catalog.publish(actor,suite.id());
    }
    public CatalogResource copy(WorkspaceActor actor,String templateId) {
        var template=templates.stream().filter(item -> item.id().equals(templateId)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown template"));
        return catalog.create(actor,template.kind(),template.name(),template.content());
    }
}
