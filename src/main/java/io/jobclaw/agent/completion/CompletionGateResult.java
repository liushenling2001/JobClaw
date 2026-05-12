package io.jobclaw.agent.completion;

import java.util.List;

public record CompletionGateResult(
        boolean registered,
        boolean passed,
        boolean canRetry,
        int failedAttempts,
        int maxAttempts,
        List<String> failures,
        String recoveryInstruction
) {
    public static CompletionGateResult unregistered() {
        return new CompletionGateResult(false, true, false, 0, 0, List.of(), "");
    }

    public String toModelMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("Completion check failed. Do not final answer yet.\n\n");
        sb.append("Failed checks:\n");
        for (String failure : failures) {
            sb.append("- ").append(failure).append("\n");
        }
        sb.append("\nRecovery instruction from skill:\n");
        sb.append(recoveryInstruction == null || recoveryInstruction.isBlank()
                ? "Continue the task according to the active skill. Do not final answer until all checks pass."
                : recoveryInstruction.trim());
        sb.append("\n\nRecovery attempt: ").append(failedAttempts).append("/").append(maxAttempts).append(".");
        return sb.toString();
    }
}
