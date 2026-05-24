package io.jobclaw.board;

import java.time.Instant;

public record BoardRecord(
        String boardId,
        String runId,
        String title,
        Instant createdAt
) {
}
