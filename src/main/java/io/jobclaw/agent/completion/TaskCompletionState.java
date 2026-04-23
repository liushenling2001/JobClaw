package io.jobclaw.agent.completion;

import io.jobclaw.agent.TaskHarnessPhase;

import java.util.List;
import java.util.Set;

public record TaskCompletionState(
        boolean worklistPlanned,
        int pendingSubtasks,
        int completedSubtasks,
        int failedSubtasks,
        int activeSubagents,
        int activeTools,
        boolean artifactsFound,
        boolean documentToolUsed,
        boolean mutatingFileToolUsed,
        int incompletePlanSteps,
        Set<TaskHarnessPhase> phasesCompleted,
        boolean hasUsableFinalResponse,
        boolean hasPlanningOnlyResponse,
        String lastFailureType,
        String lastFailureReason,
        List<String> artifactPaths
) {
}
