package io.jobclaw.summary;

import java.time.Instant;
import java.util.List;

public record ChunkSummary(
        String chunkId,
        String sessionId,
        String summaryText,
        List<String> entities,
        List<String> topics,
        List<String> decisions,
        List<String> openQuestions,
        int version,
        Instant createdAt
) {
}
