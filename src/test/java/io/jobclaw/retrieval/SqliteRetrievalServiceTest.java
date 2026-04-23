package io.jobclaw.retrieval;

import io.jobclaw.conversation.StoredMessage;
import io.jobclaw.conversation.file.FileConversationStore;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteRetrievalServiceTest {

    private static final String SQLITE_TMPDIR_PROPERTY = "org.sqlite.tmpdir";

    @TempDir
    Path tempDir;

    @Test
    void configuresSqliteNativeTempDirUnderJobclawHome() {
        String previous = System.getProperty(SQLITE_TMPDIR_PROPERTY);
        System.clearProperty(SQLITE_TMPDIR_PROPERTY);
        try {
            Path dbPath = tempDir.resolve("retrieval").resolve("search.db");
            new SqliteRetrievalService(
                    new FileConversationStore(tempDir.resolve("conversation").toString()),
                    new FileSummaryService(tempDir.resolve("summary").toString()),
                    dbPath.toString()
            );

            Path expected = Path.of(System.getProperty("user.home"), ".jobclaw", "native", "sqlite");
            assertEquals(expected.toString(), System.getProperty(SQLITE_TMPDIR_PROPERTY));
            assertTrue(java.nio.file.Files.isDirectory(expected));
        } finally {
            if (previous == null) {
                System.clearProperty(SQLITE_TMPDIR_PROPERTY);
            } else {
                System.setProperty(SQLITE_TMPDIR_PROPERTY, previous);
            }
        }
    }

    @Test
    void searchesHistorySummariesAndMemoryFacts() {
        FileConversationStore conversationStore = new FileConversationStore(tempDir.toString());
        FileSummaryService summaryService = new FileSummaryService(tempDir.toString());
        Instant now = Instant.now();
        Instant oneDayAgo = now.minusSeconds(86_400);

        conversationStore.appendMessage(new StoredMessage(
                "m1",
                "session-1",
                1,
                "user",
                "Please inspect D:\\workspace\\jobclaw\\README.md for deployment steps",
                null,
                null,
                null,
                null,
                Map.of(),
                oneDayAgo
        ));
        conversationStore.appendMessage(new StoredMessage(
                "m2",
                "session-1",
                2,
                "assistant",
                "README deployment steps are in the backend release checklist",
                null,
                null,
                null,
                null,
                Map.of(),
                now
        ));

        summaryService.saveChunkSummary(new ChunkSummary(
                "chunk-1",
                "session-1",
                "Deployment discussion and README review",
                List.of("README.md"),
                List.of("deployment"),
                List.of("keep backend-only rollout"),
                List.of("who owns release verification"),
                1,
                now
        ));
        summaryService.saveSessionSummary(new SessionSummaryRecord(
                "session-1",
                "The conversation focuses on deployment workflow.",
                List.of(),
                List.of("Keep backend-only changes"),
                List.of("D:\\workspace\\jobclaw\\README.md"),
                1,
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
                        "Keep backend-only changes",
                        Map.of(),
                        0.9,
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

        assertFalse(retrievalService.searchHistory(
                new SearchQuery("session-1", "README", null, null, null, 5, null)
        ).isEmpty());
        assertFalse(retrievalService.searchSummaries(
                new SearchQuery("session-1", "deployment", null, null, null, 5, null)
        ).isEmpty());
        assertFalse(retrievalService.searchMemory(
                new SearchQuery("session-1", "backend-only", null, null, null, 5, null)
        ).isEmpty());
        assertFalse(retrievalService.searchSummaries(
                new SearchQuery("session-1", "verification", null, null, null, 5, null)
        ).isEmpty());
        assertFalse(retrievalService.searchMemory(
                new SearchQuery("session-1", "constraint", null, null, null, 5, null)
        ).isEmpty());

        List<StoredMessage> filteredHistory = retrievalService.searchHistory(
                new SearchQuery("session-1", "README", "assistant", now.minusSeconds(60), null, 5, null)
        );
        assertEquals(1, filteredHistory.size());
        assertEquals("m2", filteredHistory.get(0).messageId());

        List<ChunkSummary> filteredSummaries = retrievalService.searchSummaries(
                new SearchQuery("session-1", "deployment", null, now.minusSeconds(60), null, 5, null)
        );
        assertEquals(1, filteredSummaries.size());

        List<MemoryFact> filteredMemory = retrievalService.searchMemory(
                new SearchQuery("session-1", "backend-only", "constraint", now.minusSeconds(60), null, 5, null)
        );
        assertEquals(1, filteredMemory.size());
    }
}
