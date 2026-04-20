package io.jobclaw.agent.completion;

import io.jobclaw.agent.TaskHarnessPhase;
import io.jobclaw.agent.planning.TaskPlanningMode;

import java.util.List;

public record DoneDefinition(
        TaskPlanningMode planningMode,
        DeliveryType deliveryType,
        List<String> requiredArtifacts,
        List<TaskHarnessPhase> requiredPhases,
        boolean requiresWorklist,
        boolean requiresFinalSummary,
        boolean acceptOptionalFollowUp,
        List<String> completionRules
) {
}
