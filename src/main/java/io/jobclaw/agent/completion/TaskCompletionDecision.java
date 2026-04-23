package io.jobclaw.agent.completion;

import java.util.List;

public record TaskCompletionDecision(
        Status status,
        String reason,
        List<String> missingRequirements
) {
    public enum Status {
        COMPLETE,
        CONTINUE,
        PLAN_REVIEW,
        REPAIR,
        BLOCKED
    }

    public static TaskCompletionDecision complete(String reason) {
        return new TaskCompletionDecision(Status.COMPLETE, reason, List.of());
    }

    public static TaskCompletionDecision cont(String reason, List<String> missingRequirements) {
        return new TaskCompletionDecision(Status.CONTINUE, reason, missingRequirements == null ? List.of() : List.copyOf(missingRequirements));
    }

    public static TaskCompletionDecision repair(String reason, List<String> missingRequirements) {
        return new TaskCompletionDecision(Status.REPAIR, reason, missingRequirements == null ? List.of() : List.copyOf(missingRequirements));
    }

    public static TaskCompletionDecision planReview(String reason, List<String> missingRequirements) {
        return new TaskCompletionDecision(Status.PLAN_REVIEW, reason, missingRequirements == null ? List.of() : List.copyOf(missingRequirements));
    }

    public static TaskCompletionDecision blocked(String reason, List<String> missingRequirements) {
        return new TaskCompletionDecision(Status.BLOCKED, reason, missingRequirements == null ? List.of() : List.copyOf(missingRequirements));
    }
}
