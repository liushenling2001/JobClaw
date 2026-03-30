package io.jobclaw.conversation;

import java.time.Instant;

public record MessageChunk(
        String chunkId,
        String sessionId,
        long startSequence,
        long endSequence,
        int messageCount,
        Integer tokenEstimate,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
