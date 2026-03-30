package io.jobclaw.integration;

import io.jobclaw.conversation.file.FileConversationStore;
import io.jobclaw.providers.Message;
import io.jobclaw.providers.ToolCall;
import io.jobclaw.retrieval.SearchQuery;
import io.jobclaw.retrieval.SqliteRetrievalService;
import io.jobclaw.session.Session;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.SessionSummaryRecord;
import io.jobclaw.summary.file.FileSummaryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConversationProjectionIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void toolMessagesPersistProjectAndRemainSearchable() {
        FileConversationStore conversationStore = new FileConversationStore(tempDir.resolve("conversation").toString());
        FileSummaryService summaryService = new FileSummaryService(tempDir.resolve("conversation").toString());
        SessionManager sessionManager = new SessionManager(tempDir.toString(), conversationStore, summaryService);

        sessionManager.addMessage("session-1", "user", "Read README and summarize deployment steps");

        Message assistant = Message.assistant("");
        assistant.setToolCalls(List.of(new ToolCall("tool-1", "read_file", "{\"path\":\"README.md\"}")));
        sessionManager.addFullMessage("session-1", assistant);
        sessionManager.addFullMessage("session-1", Message.tool("tool-1", "README deployment section"));
        sessionManager.addMessage("session-1", "assistant", "Deployment steps are documented in README.");
        summaryService.saveSessionSummary(new SessionSummaryRecord(
                "session-1",
                "Deployment discussion summary",
                List.of(),
                List.of(),
                List.of(),
                4,
                1,
                Instant.now()
        ));

        Session projected = new SessionManager(tempDir.toString(), conversationStore, summaryService).getSession("session-1");
        assertEquals(4, projected.getMessages().size());
        assertEquals("read_file", projected.getMessages().get(1).getToolCalls().get(0).getFunction().getName());
        assertEquals("tool", projected.getMessages().get(2).getRole());
        assertEquals("tool-1", projected.getMessages().get(2).getToolCallId());

        SqliteRetrievalService retrievalService = new SqliteRetrievalService(
                conversationStore,
                summaryService,
                tempDir.resolve("search.db").toString()
        );

        assertFalse(retrievalService.searchHistory(
                new SearchQuery("session-1", "deployment section", "tool", null, null, 5, null)
        ).isEmpty());
    }
}
