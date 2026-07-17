package io.jobclaw.providers;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DeepSeekMessageProtocolNormalizerTest {

    @Test
    void convertsOrphanToolMessageToUserContextForDeepSeek() {
        List<Message> normalized = DeepSeekMessageProtocolNormalizer.normalize(List.of(
                Message.user("继续"),
                Message.tool("read_file_1", "file content")
        ));

        assertEquals(2, normalized.size());
        assertEquals("user", normalized.get(1).getRole());
        assertFalse(normalized.get(1).getContent().isBlank());
        assertEquals("Tool result from read_file_1:\nfile content", normalized.get(1).getContent());
    }

    @Test
    void preservesToolMessageAfterMatchingAssistantToolCallForDeepSeek() {
        Message assistant = Message.assistant("");
        assistant.setToolCalls(List.of(new ToolCall("call-1", "read_file", "{\"path\":\"a.txt\"}")));

        List<Message> normalized = DeepSeekMessageProtocolNormalizer.normalize(List.of(
                Message.user("读文件"),
                assistant,
                Message.tool("call-1", "file content")
        ));

        assertEquals(3, normalized.size());
        assertEquals("assistant", normalized.get(1).getRole());
        assertEquals("tool", normalized.get(2).getRole());
        assertEquals("call-1", normalized.get(2).getToolCallId());
    }
}
