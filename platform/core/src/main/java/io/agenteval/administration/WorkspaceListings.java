package io.agenteval.administration;

import io.agenteval.catalog.ResourceKind;
import io.agenteval.identity.WorkspaceActor;
import java.time.Instant;
import java.util.*;

public interface WorkspaceListings {
    record ResourceSummary(UUID id,ResourceKind kind,String name,long revision,boolean archived) {}
    record ExperimentSummary(UUID id,UUID workspaceId,UUID agentId,String agentVersion,UUID datasetVersionId,
        UUID suiteVersionId,int repeatCount,Instant deadline,String status,UUID sourceExperimentId,
        String agentName,String datasetName,int datasetVersionNumber,String suiteName,int suiteVersionNumber,
        int caseCount,int taskCount,Instant createdAt, UUID sdkProjectId, String sdkProject, String namespace, String runName, Boolean evaluationAuthorized) {}
    List<ResourceSummary> catalog(WorkspaceActor actor,ResourceKind kind,int offset,int limit);
    List<ExperimentSummary> experiments(WorkspaceActor actor,int offset,int limit);
    ExperimentSummary experiment(WorkspaceActor actor,UUID id);
    static void page(int offset,int limit) { if(offset<0 || limit<1 || limit>100) throw new IllegalArgumentException("Invalid page"); }
}
