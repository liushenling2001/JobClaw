package io.jobclaw.providers;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * DeepSeek rejects orphan tool messages more strictly than some other providers.
 */
public final class DeepSeekMessageProtocolNormalizer {

    private DeepSeekMessageProtocolNormalizer() {
    }

    public static List<Message> normalize(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<Message> normalized = new ArrayList<>();
        Set<String> pendingToolCallIds = new LinkedHashSet<>();
        for (Message message : messages) {
            if (message == null || message.getRole() == null) {
                continue;
            }
            if ("tool".equals(message.getRole())) {
                String toolCallId = message.getToolCallId();
                if (toolCallId != null && pendingToolCallIds.remove(toolCallId)) {
                    normalized.add(copy(message));
                } else {
                    normalized.add(orphanToolResultAsUserMessage(message));
                }
                continue;
            }

            pendingToolCallIds.clear();
            Message copied = copy(message);
            normalized.add(copied);
            if ("assistant".equals(copied.getRole()) && copied.getToolCalls() != null) {
                for (ToolCall toolCall : copied.getToolCalls()) {
                    if (toolCall != null && toolCall.getId() != null && !toolCall.getId().isBlank()) {
                        pendingToolCallIds.add(toolCall.getId());
                    }
                }
            }
        }
        return normalized;
    }

    private static Message orphanToolResultAsUserMessage(Message message) {
        String toolName = message.getToolCallId() != null && !message.getToolCallId().isBlank()
                ? message.getToolCallId()
                : "tool";
        Message converted = Message.user("Tool result from " + toolName + ":\n"
                + (message.getContent() != null ? message.getContent() : ""));
        converted.setImages(message.getImages());
        return converted;
    }

    private static Message copy(Message message) {
        Message copied = new Message(message.getRole(), message.getContent());
        copied.setImages(message.getImages());
        copied.setToolCalls(message.getToolCalls());
        copied.setToolCallId(message.getToolCallId());
        return copied;
    }
}
