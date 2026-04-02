package io.jobclaw.agent;

public record TaskHarnessVerificationResult(boolean success, String reason, String failureType) {

    public TaskHarnessVerificationResult(boolean success, String reason) {
        this(success, reason, null);
    }

    public static TaskHarnessVerificationResult ok(String reason) {
        return new TaskHarnessVerificationResult(true, reason, null);
    }

    public static TaskHarnessVerificationResult fail(String reason) {
        return new TaskHarnessVerificationResult(false, reason, null);
    }

    public static TaskHarnessVerificationResult fail(String failureType, String reason) {
        return new TaskHarnessVerificationResult(false, reason, failureType);
    }
}
