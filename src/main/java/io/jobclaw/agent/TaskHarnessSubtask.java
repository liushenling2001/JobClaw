package io.jobclaw.agent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record TaskHarnessSubtask(
        String id,
        String title,
        TaskHarnessSubtaskStatus status,
        String summary,
        String childSessionId,
        Instant startedAt,
        Instant completedAt,
        Map<String, Object> metadata
) {

    public TaskHarnessSubtask {
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public TaskHarnessSubtask withStatus(TaskHarnessSubtaskStatus nextStatus,
                                         String nextSummary,
                                         String nextChildSessionId,
                                         Instant nextStartedAt,
                                         Instant nextCompletedAt,
                                         Map<String, Object> nextMetadata) {
        return new TaskHarnessSubtask(
                id,
                title,
                nextStatus,
                nextSummary != null ? nextSummary : summary,
                nextChildSessionId != null ? nextChildSessionId : childSessionId,
                nextStartedAt != null ? nextStartedAt : startedAt,
                nextCompletedAt != null ? nextCompletedAt : completedAt,
                nextMetadata != null ? nextMetadata : metadata
        );
    }
}
