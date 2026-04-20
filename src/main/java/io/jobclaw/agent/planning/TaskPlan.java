package io.jobclaw.agent.planning;

import io.jobclaw.agent.completion.DoneDefinition;

public record TaskPlan(
        TaskPlanningMode planningMode,
        DoneDefinition doneDefinition,
        String reason
) {
}
