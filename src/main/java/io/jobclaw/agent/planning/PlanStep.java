package io.jobclaw.agent.planning;

public record PlanStep(
        String id,
        String goal,
        String completion
) {
    public PlanStep {
        id = id != null ? id : "";
        goal = goal != null ? goal : "";
        completion = completion != null ? completion : "";
    }
}
