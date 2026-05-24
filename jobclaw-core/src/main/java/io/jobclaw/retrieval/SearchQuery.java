package io.jobclaw.retrieval;

import java.time.Instant;
import java.util.Map;

public record SearchQuery(
        String sessionId,
        String queryText,
        String role,
        Instant from,
        Instant to,
        int limit,
        Map<String, Object> metadataFilters
) {
}
