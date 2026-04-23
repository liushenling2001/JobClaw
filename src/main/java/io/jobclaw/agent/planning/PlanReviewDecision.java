package io.jobclaw.agent.planning;

import java.util.List;

public record PlanReviewDecision(
        PlanReviewAction action,
        String reason,
        List<String> instructions
) {
    public PlanReviewDecision {
        action = action != null ? action : PlanReviewAction.KEEP_PLAN;
        reason = reason != null ? reason : "";
        instructions = instructions != null ? List.copyOf(instructions) : List.of();
    }

    public static PlanReviewDecision keep(String reason) {
        return new PlanReviewDecision(PlanReviewAction.KEEP_PLAN, reason, List.of());
    }

    public static PlanReviewDecision of(PlanReviewAction action, String reason, List<String> instructions) {
        return new PlanReviewDecision(action, reason, instructions);
    }
}
