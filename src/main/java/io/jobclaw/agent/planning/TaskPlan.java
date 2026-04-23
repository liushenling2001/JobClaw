package io.jobclaw.agent.planning;

import io.jobclaw.agent.completion.DoneDefinition;

import java.util.List;

public record TaskPlan(
        TaskPlanningMode planningMode,
        DoneDefinition doneDefinition,
        String reason,
        List<PlanStep> steps
) {
    public TaskPlan(TaskPlanningMode planningMode, DoneDefinition doneDefinition, String reason) {
        this(planningMode, doneDefinition, reason, List.of());
    }

    public TaskPlan {
        steps = steps != null ? List.copyOf(steps) : List.of();
    }
}
