package io.jobclaw.agent.artifact;

import java.time.Instant;

public record RunArtifact(
        String runId,
        String stepId,
        String name,
        String path,
        String summary,
        long contentLength,
        Instant createdAt
) {
}
