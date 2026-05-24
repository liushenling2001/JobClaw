package io.jobclaw.agent;

import io.jobclaw.agent.evolution.MemoryStore;
import io.jobclaw.config.AgentConfig;
import io.jobclaw.conversation.StoredMessage;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.ChunkSummary;
import io.jobclaw.summary.MemoryFact;
import io.jobclaw.summary.SessionSummaryRecord;
import io.jobclaw.summary.SummaryService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionSummarizerStructuredParseTest {

    @Test
    void parsesStructuredChunkSections() throws Exception {
        Path tempMemoryStore = Files.createTempDirectory("jobclaw-memory-store");
        SessionManager sessionManager = new SessionManager();
        AgentConfig agentConfig = new AgentConfig();
        SummaryService summaryService = new NoopSummaryService();
        SessionSummarizer summarizer = new SessionSummarizer(
                sessionManager,
                null,
                agentConfig,
                new MemoryStore(tempMemoryStore.toString()),
                null,
                summaryService
        );

        Method method = SessionSummarizer.class.getDeclaredMethod("parseStructuredChunkData", String.class);
        method.setAccessible(true);
        Object result = method.invoke(summarizer, """
                ENTITIES:
                - JobClaw
                - SessionManager
                TOPICS:
                - backend refactor
                DECISIONS:
                - keep frontend unchanged
                OPEN_QUESTIONS:
                - how to version chunk summaries
                """);

        Method entities = result.getClass().getDeclaredMethod("entities");
        Method topics = result.getClass().getDeclaredMethod("topics");
        Method decisions = result.getClass().getDeclaredMethod("decisions");
        Method openQuestions = result.getClass().getDeclaredMethod("openQuestions");

        assertEquals(List.of("JobClaw", "SessionManager"), entities.invoke(result));
        assertEquals(List.of("backend refactor"), topics.invoke(result));
        assertEquals(List.of("keep frontend unchanged"), decisions.invoke(result));
        assertEquals(List.of("how to version chunk summaries"), openQuestions.invoke(result));
    }

    @Test
    void parsesLlmMemoryFacts() throws Exception {
        Path tempMemoryStore = Files.createTempDirectory("jobclaw-memory-store");
        SessionManager sessionManager = new SessionManager();
        AgentConfig agentConfig = new AgentConfig();
        SummaryService summaryService = new NoopSummaryService();
        SessionSummarizer summarizer = new SessionSummarizer(
                sessionManager,
                null,
                agentConfig,
                new MemoryStore(tempMemoryStore.toString()),
                null,
                summaryService
        );

        Method method = SessionSummarizer.class.getDeclaredMethod(
                "parseMemoryFacts",
                String.class,
                List.class,
                String.class,
                Instant.class
        );
        method.setAccessible(true);

        List<StoredMessage> messages = List.of(
                new StoredMessage("m1", "session-1", 1, "user", "Keep frontend unchanged", null, null, null, null, java.util.Map.of(), Instant.now())
        );
        @SuppressWarnings("unchecked")
        List<MemoryFact> facts = (List<MemoryFact>) method.invoke(
                summarizer,
                "session-1",
                messages,
                """
                FACT|constraint|user|requires|Keep frontend unchanged|0.9
                FACT|important_file|conversation|mentions|D:\\workspace\\jobclaw\\README.md|0.8
                """,
                Instant.now()
        );

        assertEquals(2, facts.size());
        assertEquals("constraint", facts.get(0).factType());
        assertEquals("Keep frontend unchanged", facts.get(0).objectText());
        assertEquals("important_file", facts.get(1).factType());
    }

    private static class NoopSummaryService implements SummaryService {
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
            return Optional.empty();
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
