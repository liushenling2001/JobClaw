package io.jobclaw.summary;

import java.time.Instant;
import java.util.Map;

public record MemoryFact(
        String factId,
        String sessionId,
        String scope,
        String factType,
        String subject,
        String predicate,
        String objectText,
        Map<String, Object> evidence,
        double confidence,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
