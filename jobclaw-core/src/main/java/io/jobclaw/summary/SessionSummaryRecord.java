package io.jobclaw.summary;

import java.time.Instant;
import java.util.List;

public record SessionSummaryRecord(
        String sessionId,
        String summaryText,
        List<String> activeGoals,
        List<String> constraints,
        List<String> importantFiles,
        long sourceChunkEndSequence,
        int version,
        Instant updatedAt
) {
}
