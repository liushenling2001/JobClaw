package io.jobclaw.integration;

import io.jobclaw.context.ContextAssemblyOptions;
import io.jobclaw.context.DefaultContextAssembler;
import io.jobclaw.conversation.file.FileConversationStore;
import io.jobclaw.providers.Message;
import io.jobclaw.retrieval.SqliteRetrievalService;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.ChunkSummary;
import io.jobclaw.summary.MemoryFact;
import io.jobclaw.summary.SessionSummaryRecord;
import io.jobclaw.summary.file.FileSummaryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextAssemblyIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void assemblesSessionSummaryMemoryAndRetrievedHistoryTogether() {
        FileConversationStore conversationStore = new FileConversationStore(tempDir.resolve("conversation").toString());
        FileSummaryService summaryService = new FileSummaryService(tempDir.resolve("conversation").toString());
        SessionManager sessionManager = new SessionManager(tempDir.toString(), conversationStore);

        sessionManager.addMessage("session-1", "user", "We should keep backend-only rollout for this refactor.");
        sessionManager.addMessage("session-1", "assistant", "Understood, backend-only rollout stays.");
        sessionManager.addMessage("session-1", "user", "Search README deployment checklist.");
        sessionManager.addMessage("session-1", "assistant", "The README deployment checklist is relevant.");

        Instant now = Instant.now();
        summaryService.saveSessionSummary(new SessionSummaryRecord(
                "session-1",
                "The session is about backend-only refactoring and deployment workflow.",
                List.of("finish backend refactor"),
                List.of("keep backend-only rollout"),
                List.of("README.md"),
                4,
                1,
                now
        ));
        summaryService.saveChunkSummary(new ChunkSummary(
                "chunk-1",
                "session-1",
                "Deployment checklist and backend-only rollout were discussed.",
                List.of("README.md"),
                List.of("deployment"),
                List.of("keep backend-only rollout"),
                List.of("who validates release"),
                1,
                now
        ));
        summaryService.replaceMemoryFacts("session-1", List.of(
                new MemoryFact(
                        "fact-1",
                        "session-1",
                        "session",
                        "constraint",
                        "user",
                        "requires",
                        "keep backend-only rollout",
                        Map.of(),
                        0.95,
                        true,
                        now,
                        now
                )
        ));

        SqliteRetrievalService retrievalService = new SqliteRetrievalService(
                conversationStore,
                summaryService,
                tempDir.resolve("search.db").toString()
        );
        DefaultContextAssembler assembler = new DefaultContextAssembler(sessionManager, 4, retrievalService);

        List<Message> assembled = assembler.assemble(
                "session-1",
                "How should we handle deployment for the backend-only rollout?",
                new ContextAssemblyOptions(2, 4, 3, 3, 4096)
        );

        String joined = assembled.stream()
                .map(Message::getContent)
                .filter(content -> content != null && !content.isBlank())
                .reduce("", (left, right) -> left + "\n" + right);

        assertTrue(joined.contains("backend-only rollout"));
        assertTrue(joined.contains("deployment"));
        assertTrue(joined.contains("README"));
        assertTrue(assembled.stream().anyMatch(message -> "system".equals(message.getRole())));
        assertTrue(assembled.stream().anyMatch(message -> "assistant".equals(message.getRole())));
    }
}
