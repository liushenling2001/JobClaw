package io.jobclaw.agent.planning;

public record TaskPlanningDecision(
        TaskPlanningMode mode,
        String reason
) {
}
