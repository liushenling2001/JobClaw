package io.jobclaw.agent.completion;

import io.jobclaw.agent.TaskHarnessPhase;
import io.jobclaw.agent.planning.TaskPlanningMode;

import java.util.List;

public record DoneDefinition(
        TaskPlanningMode planningMode,
        DeliveryType deliveryType,
        List<String> requiredArtifacts,
        List<String> requiredArtifactDirectories,
        List<TaskHarnessPhase> requiredPhases,
        boolean requiresWorklist,
        WorklistRequirementSource worklistRequirementSource,
        boolean requiresFinalSummary,
        boolean acceptOptionalFollowUp,
        ContractSource contractSource,
        List<String> completionRules
) {
    public DoneDefinition(TaskPlanningMode planningMode,
                          DeliveryType deliveryType,
                          List<String> requiredArtifacts,
                          List<TaskHarnessPhase> requiredPhases,
                          boolean requiresWorklist,
                          boolean requiresFinalSummary,
                          boolean acceptOptionalFollowUp,
                          List<String> completionRules) {
        this(planningMode, deliveryType, requiredArtifacts, List.of(), requiredPhases, requiresWorklist,
                requiresWorklist ? WorklistRequirementSource.AGENT_PLAN : WorklistRequirementSource.NONE,
                requiresFinalSummary, acceptOptionalFollowUp, ContractSource.POLICY_FALLBACK, completionRules);
    }

    public DoneDefinition(TaskPlanningMode planningMode,
                          DeliveryType deliveryType,
                          List<String> requiredArtifacts,
                          List<String> requiredArtifactDirectories,
                          List<TaskHarnessPhase> requiredPhases,
                          boolean requiresWorklist,
                          boolean requiresFinalSummary,
                          boolean acceptOptionalFollowUp,
                          List<String> completionRules) {
        this(planningMode, deliveryType, requiredArtifacts, requiredArtifactDirectories, requiredPhases, requiresWorklist,
                requiresWorklist ? WorklistRequirementSource.AGENT_PLAN : WorklistRequirementSource.NONE,
                requiresFinalSummary, acceptOptionalFollowUp, ContractSource.POLICY_FALLBACK, completionRules);
    }

    public DoneDefinition {
        requiredArtifacts = requiredArtifacts != null ? List.copyOf(requiredArtifacts) : List.of();
        requiredArtifactDirectories = requiredArtifactDirectories != null ? List.copyOf(requiredArtifactDirectories) : List.of();
        requiredPhases = requiredPhases != null ? List.copyOf(requiredPhases) : List.of();
        completionRules = completionRules != null ? List.copyOf(completionRules) : List.of();
        worklistRequirementSource = worklistRequirementSource != null ? worklistRequirementSource : WorklistRequirementSource.NONE;
        contractSource = contractSource != null ? contractSource : ContractSource.POLICY_FALLBACK;
    }
}
