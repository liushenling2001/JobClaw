package io.jobclaw.agent;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public record TaskHarnessStep(
        int index,
        TaskHarnessPhase phase,
        String status,
        String label,
        String detail,
        Instant timestamp,
        Map<String, Object> metadata
) {
    public TaskHarnessStep {
        detail = detail != null ? detail : "";
        timestamp = timestamp != null ? timestamp : Instant.now();
        metadata = metadata != null ? Collections.unmodifiableMap(new HashMap<>(metadata)) : Map.of();
    }
}
