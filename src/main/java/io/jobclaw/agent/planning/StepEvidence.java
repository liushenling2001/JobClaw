package io.jobclaw.agent.planning;

import java.time.Instant;
import java.util.Map;

public record StepEvidence(
        String type,
        String summary,
        Map<String, Object> metadata,
        String artifactPath,
        Instant recordedAt
) {
    public StepEvidence(String type, String summary, Map<String, Object> metadata, String artifactPath) {
        this(type, summary, metadata, artifactPath, Instant.now());
    }

    public StepEvidence {
        type = type != null ? type : "event";
        summary = summary != null ? summary : "";
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        artifactPath = artifactPath != null ? artifactPath : "";
        recordedAt = recordedAt != null ? recordedAt : Instant.now();
    }
}
