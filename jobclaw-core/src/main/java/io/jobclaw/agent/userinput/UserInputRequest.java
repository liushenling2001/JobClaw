package io.jobclaw.agent.userinput;

import java.time.Instant;
import java.util.List;

public record UserInputRequest(
        String sessionKey,
        String runId,
        String requestId,
        String question,
        String reason,
        String requiredFor,
        String resumeKey,
        List<String> options,
        Instant createdAt
) {
}
