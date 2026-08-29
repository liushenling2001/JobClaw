package io.jobclaw.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AgentLoopSystemMessageNormalizationTest {

    @Test
    void mergesSystemMessagesAtTheBeginningWithoutChangingConversationOrder() {
        List<Message> original = List.of(
                new SystemMessage("main prompt"),
                new UserMessage("first question"),
                new SystemMessage("session summary"),
                new AssistantMessage("first answer"),
                new SystemMessage("memory facts"),
                new UserMessage("current question")
        );

        List<Message> normalized = AgentLoop.normalizeOpenAiCompatibleSystemMessages(original);

        assertEquals(4, normalized.size());
        SystemMessage system = assertInstanceOf(SystemMessage.class, normalized.get(0));
        assertEquals("main prompt\n\nsession summary\n\nmemory facts", system.getText());
        assertEquals("first question", normalized.get(1).getText());
        assertEquals("first answer", normalized.get(2).getText());
        assertEquals("current question", normalized.get(3).getText());
        assertEquals(6, original.size());
    }
}
