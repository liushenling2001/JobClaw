package io.jobclaw.session;

import io.jobclaw.conversation.file.FileConversationStore;
import io.jobclaw.conversation.SessionRecord;
import io.jobclaw.providers.Message;
import io.jobclaw.providers.ToolCall;
import io.jobclaw.summary.SessionSummaryRecord;
import io.jobclaw.summary.file.FileSummaryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerProjectionTest {

    @TempDir
    Path tempDir;

    @Test
    void rebuildsSessionFromConversationStoreWithToolMetadata() {
        FileConversationStore conversationStore = new FileConversationStore(tempDir.resolve("conversation").toString());
        FileSummaryService summaryService = new FileSummaryService(tempDir.resolve("conversation").toString());
        SessionManager sessionManager = new SessionManager(tempDir.toString(), conversationStore, summaryService);

        Message assistant = Message.assistant("Calling read_file");
        assistant.setToolCalls(List.of(new ToolCall("call-1", "read_file", "{\"path\":\"README.md\"}")));
        sessionManager.addFullMessage("session-1", assistant);
        sessionManager.addFullMessage("session-1", Message.tool("call-1", "README content"));
        summaryService.saveSessionSummary(new SessionSummaryRecord(
                "session-1",
                "backend-only summary",
                List.of(),
                List.of(),
                List.of(),
                2,
                1,
                Instant.now()
        ));

        SessionManager reloaded = new SessionManager(tempDir.toString(), conversationStore, summaryService);
        Session session = reloaded.getSession("session-1");

        assertNotNull(session);
        assertEquals(2, session.getMessages().size());
        assertEquals("assistant", session.getMessages().get(0).getRole());
        assertEquals("call-1", session.getMessages().get(0).getToolCalls().get(0).getId());
        assertEquals("call-1", session.getMessages().get(1).getToolCallId());
        assertEquals("backend-only summary", session.getSummary());
    }

    @Test
    void sessionKeysAndCountIncludeConversationStoreSessions() {
        FileConversationStore conversationStore = new FileConversationStore(tempDir.resolve("conversation").toString());
        SessionManager sessionManager = new SessionManager(tempDir.toString(), conversationStore, null);

        sessionManager.addMessage("session-a", "user", "hello");
        sessionManager.addMessage("session-b", "assistant", "world");

        SessionManager reloaded = new SessionManager(tempDir.toString(), conversationStore, null);

        assertEquals(2, reloaded.getSessionCount());
        assertEquals(List.of("session-a", "session-b"), reloaded.getSessionKeys().stream().sorted().toList());
    }

    @Test
    void readingLegacySessionRecordBackfillsMessageCount() throws Exception {
        FileConversationStore conversationStore = new FileConversationStore(tempDir.resolve("conversation").toString());
        SessionManager sessionManager = new SessionManager(tempDir.toString(), conversationStore, null);

        sessionManager.addMessage("legacy-session", "user", "one");
        sessionManager.addMessage("legacy-session", "assistant", "two");

        Path sessionFile = tempDir.resolve("conversation").resolve("legacy-session").resolve("session.json");
        String legacyJson = Files.readString(sessionFile).replaceFirst("\"messageCount\"\\s*:\\s*\\d+,\\s*", "");
        Files.writeString(sessionFile, legacyJson);

        SessionRecord record = conversationStore.getSession("legacy-session").orElseThrow();

        assertEquals(2, record.getMessageCount());
        assertTrue(Files.readString(sessionFile).contains("\"messageCount\" : 2"));
    }
}
