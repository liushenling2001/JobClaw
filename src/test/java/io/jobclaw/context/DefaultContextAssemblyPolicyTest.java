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

        assertEquals(4, options.retrievedSummaryLimit());
        assertEquals(0, options.retrievedHistoryLimit());
        assertEquals(102_400, options.maxPromptTokens());
        assertEquals(20_480, options.recentMessageTokenBudget());
    }

    @Test
    void reservesModelOutputWithinTheUnifiedTrigger() {
        AgentConfig config = new AgentConfig();
        config.setContextWindow(32_000);
        SessionManager sessionManager = new SessionManager();
        SummaryService summaryService = new StubSummaryService(false);

        DefaultContextAssemblyPolicy policy = new DefaultContextAssemblyPolicy(
                config, sessionManager, summaryService, () -> 32_000, () -> 8_000);

        ContextAssemblyOptions options = policy.buildOptions("session-2", "x".repeat(9000));

        assertEquals(21_952, options.maxPromptTokens());
        assertEquals(21_952, options.recentMessageTokenBudget());
        assertEquals(0, options.retrievedHistoryLimit());
    }

    @Test
    void respectsOnlyTheUnifiedContextPercentages() {
        AgentConfig config = new AgentConfig();
        config.setContextWindow(40_000);
        config.setCompactionTriggerPercentage(70);
        config.setCompactionRetainPercentage(20);
        SessionManager sessionManager = new SessionManager();
        SummaryService summaryService = new StubSummaryService(true);

        DefaultContextAssemblyPolicy policy = new DefaultContextAssemblyPolicy(
                config, sessionManager, summaryService, () -> 40_000, () -> 4_000);

        ContextAssemblyOptions options = policy.buildOptions("session-3", "x".repeat(8000));

        assertEquals(28_000, options.maxPromptTokens());
        assertEquals(8_000, options.recentMessageTokenBudget());
        assertEquals(4, options.retrievedSummaryLimit());
        assertEquals(8, options.retrievedMemoryLimit());
    }

    @Test
    void fallsBackToUnifiedDefaultsForInvalidPercentages() {
        AgentConfig config = new AgentConfig();
        config.setContextWindow(0);
        config.setCompactionTriggerPercentage(0);
        config.setCompactionRetainPercentage(0);
        SessionManager sessionManager = new SessionManager();
        SummaryService summaryService = new StubSummaryService(false);

        DefaultContextAssemblyPolicy policy =
                new DefaultContextAssemblyPolicy(config, sessionManager, summaryService);

        ContextAssemblyOptions options = policy.buildOptions("session-4", "x".repeat(2000));

        assertEquals(102_400, options.maxPromptTokens());
        assertEquals(0, options.recentMessageLimit());
        assertEquals(102_400, options.recentMessageTokenBudget());
        assertEquals(0, options.retrievedHistoryLimit());
        assertEquals(2, options.retrievedSummaryLimit());
        assertEquals(8, options.retrievedMemoryLimit());
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
