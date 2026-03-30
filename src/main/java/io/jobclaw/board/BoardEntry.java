package io.jobclaw.board;

import java.time.Instant;

public record BoardEntry(
        String entryId,
        String boardId,
        String entryType,
        String title,
        String content,
        String authorAgentId,
        String authorAgentName,
        String visibility,
        Instant createdAt
) {
}
