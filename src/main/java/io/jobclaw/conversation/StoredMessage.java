package io.jobclaw.conversation;

import java.time.Instant;
import java.util.Map;

public record StoredMessage(
        String messageId,
        String sessionId,
        long sequence,
        String role,
        String content,
        String toolName,
        String toolCallId,
        String toolArgsJson,
        String toolResultJson,
        Map<String, Object> metadata,
        Instant createdAt
) {
}
