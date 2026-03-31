package io.jobclaw.context;

import io.jobclaw.config.AgentConfig;
import io.jobclaw.conversation.StoredMessage;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.ChunkSummary;
import io.jobclaw.summary.MemoryFact;
import io.jobclaw.summary.SessionSummaryRecord;
import io.jobclaw.summary.SummaryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultContextAssemblyPolicyTest {

    @Test
    void prefersMoreSummariesWhenSessionAlreadySummarized() {
        AgentConfig config = new AgentConfig();
        config.setContextWindow(128_000);
        SessionManager sessionManager = new SessionManager();
        SummaryService summaryService = new StubSummaryService(true);

        DefaultContextAssemblyPolicy policy =
                new DefaultContextAssemblyPolicy(config, sessionManager, summaryService);

        ContextAssemblyOptions options = policy.buildOptions("session-1", "short question");

        assertTrue(options.retrievedSummaryLimit() >= 4);
        assertTrue(options.retrievedHistoryLimit() <= 4);
    }

    @Test
    void shrinksRecentAndHistoryForLongUserInput() {
        AgentConfig config = new AgentConfig();
        config.setContextWindow(32_000);
        SessionManager sessionManager = new SessionManager();
        SummaryService summaryService = new StubSummaryService(false);

        DefaultContextAssemblyPolicy policy =
                new DefaultContextAssemblyPolicy(config, sessionManager, summaryService);

        ContextAssemblyOptions options = policy.buildOptions("session-2", "x".repeat(9000));

        assertTrue(options.maxPromptTokens() <= 19_200);
        assertTrue(options.recentMessageLimit() <= 10);
        assertTrue(options.retrievedHistoryLimit() <= 4);
    }

    @Test
    void respectsConfiguredRetrievalAndPromptPercentages() {
        AgentConfig config = new AgentConfig();
        config.setContextWindow(40_000);
        config.setContextMaxPromptTokenPercentage(50);
        config.setContextLongInputPromptTokenPercentage(40);
        config.setContextLongInputTokenPercentage(4);
        config.setContextMaxHistoryRetrieval(3);
        config.setContextMaxSummaryRetrieval(5);
        config.setContextMaxMemoryRetrieval(2);
        SessionManager sessionManager = new SessionManager();
        SummaryService summaryService = new StubSummaryService(true);

        DefaultContextAssemblyPolicy policy =
                new DefaultContextAssemblyPolicy(config, sessionManager, summaryService);

        ContextAssemblyOptions options = policy.buildOptions("session-3", "x".repeat(8000));

        assertTrue(options.maxPromptTokens() <= 16_000);
        assertTrue(options.retrievedHistoryLimit() <= 3);
        assertTrue(options.retrievedSummaryLimit() <= 5);
        assertTrue(options.retrievedMemoryLimit() <= 2);
    }

    @Test
    void normalizesInvalidPolicyValues() {
        AgentConfig config = new AgentConfig();
        config.setContextWindow(0);
        config.setRecentMessagesToKeep(1);
        config.setMemoryTokenBudgetPercentage(0);
        config.setMemoryMinTokenBudget(-1);
        config.setMemoryMaxTokenBudget(32);
        config.setContextMaxPromptTokenPercentage(0);
        config.setContextLongInputPromptTokenPercentage(200);
        config.setContextLongInputTokenPercentage(0);
        config.setContextMaxHistoryRetrieval(0);
        config.setContextMaxSummaryRetrieval(99);
        config.setContextMaxMemoryRetrieval(-5);
        SessionManager sessionManager = new SessionManager();
        SummaryService summaryService = new StubSummaryService(false);

        DefaultContextAssemblyPolicy policy =
                new DefaultContextAssemblyPolicy(config, sessionManager, summaryService);

        ContextAssemblyOptions options = policy.buildOptions("session-4", "x".repeat(2000));

        assertEquals(4096, options.maxPromptTokens());
        assertEquals(8, options.recentMessageLimit());
        assertEquals(2, options.retrievedHistoryLimit());
        assertEquals(3, options.retrievedSummaryLimit());
        assertEquals(1, options.retrievedMemoryLimit());
    }

    private static class StubSummaryService implements SummaryService {
        private final boolean hasSummary;

        private StubSummaryService(boolean hasSummary) {
            this.hasSummary = hasSummary;
        }

        @Override
        public void saveChunkSummary(ChunkSummary chunkSummary) {
        }

        @Override
        public void saveSessionSummary(SessionSummaryRecord sessionSummary) {
        }

        @Override
        public void replaceMemoryFacts(String sessionId, List<MemoryFact> facts) {
        }

        @Override
        public void summarizePendingChunks(String sessionId) {
        }

        @Override
        public Optional<ChunkSummary> getChunkSummary(String chunkId) {
            return Optional.empty();
        }

        @Override
        public List<ChunkSummary> listChunkSummaries(String sessionId) {
            return List.of();
        }

        @Override
        public Optional<SessionSummaryRecord> getSessionSummary(String sessionId) {
            if (!hasSummary) {
                return Optional.empty();
            }
            return Optional.of(new SessionSummaryRecord(
                    sessionId,
                    "summary",
                    List.of(),
                    List.of(),
                    List.of(),
                    1,
                    1,
                    Instant.now()
            ));
        }

        @Override
        public List<MemoryFact> extractFacts(String sessionId, List<StoredMessage> messages) {
            return List.of();
        }

        @Override
        public List<MemoryFact> listMemoryFacts(String sessionId) {
            return List.of();
        }
    }
}
