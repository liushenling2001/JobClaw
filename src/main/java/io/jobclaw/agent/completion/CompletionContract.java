package io.jobclaw.agent.completion;

import java.time.Instant;
import java.util.List;

public record CompletionContract(
        String sessionKey,
        String runId,
        List<CompletionCheck> checks,
        String onFail,
        int maxAttempts,
        int failedAttempts,
        Instant createdAt,
        Instant updatedAt
) {
    public CompletionContract withFailedAttempts(int attempts) {
        return new CompletionContract(sessionKey, runId, checks, onFail, maxAttempts,
                attempts, createdAt, Instant.now());
    }
}
