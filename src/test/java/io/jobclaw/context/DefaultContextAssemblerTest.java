package io.jobclaw.context;

import io.jobclaw.conversation.StoredMessage;
import io.jobclaw.providers.Message;
import io.jobclaw.retrieval.RetrievalBundle;
import io.jobclaw.retrieval.RetrievalService;
import io.jobclaw.retrieval.SearchQuery;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.ChunkSummary;
import io.jobclaw.summary.MemoryFact;
import io.jobclaw.summary.SessionSummaryRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultContextAssemblerTest {

    @Test
    void trimsContextWhenPromptBudgetIsSmall() {
        SessionManager sessionManager = new SessionManager();
        sessionManager.addMessage("ctx-test", "user", repeat("recent user message ", 60));
        sessionManager.addMessage("ctx-test", "assistant", repeat("recent assistant message ", 60));

        RetrievalService retrievalService = new StubRetrievalService();
        DefaultContextAssembler assembler = new DefaultContextAssembler(sessionManager, 10, retrievalService);

        List<Message> messages = assembler.assemble(
                "ctx-test",
                "current input",
                new ContextAssemblyOptions(10, 4, 3, 4, 320)
        );

        assertFalse(messages.isEmpty());
        int totalChars = messages.stream()
                .map(Message::getContent)
                .filter(content -> content != null)
                .mapToInt(String::length)
                .sum();
        int maxContentLength = messages.stream()
                .map(Message::getContent)
                .filter(content -> content != null)
                .mapToInt(String::length)
                .max()
                .orElse(0);
        assertTrue(totalChars < 7000);
        assertTrue(maxContentLength < 1800);
    }

    @Test
    void shouldExcludeToolMessagesFromRecentHistory() {
        SessionManager sessionManager = new SessionManager();
        sessionManager.addMessage("ctx-test", "user", "请分析 excel");
        sessionManager.addMessage("ctx-test", "assistant", "我先读取文件");
        sessionManager.addFullMessage("ctx-test", Message.tool("tool-1", "A1:B999 的原始表格输出"));
        sessionManager.addMessage("ctx-test", "assistant", "分析结果：销量最高的是三月");

        DefaultContextAssembler assembler = new DefaultContextAssembler(sessionManager, 10, new StubRetrievalService());

        List<Message> messages = assembler.assemble(
                "ctx-test",
                "继续总结",
                new ContextAssemblyOptions(10, 4, 3, 4, 4096)
        );

        assertFalse(messages.stream().anyMatch(message -> "tool".equals(message.getRole())));
        assertTrue(messages.stream().anyMatch(message -> "assistant".equals(message.getRole())
                && message.getContent().contains("分析结果")));
    }

    @Test
    void shouldExcludeToolMessagesFromRetrievedHistory() {
        SessionManager sessionManager = new SessionManager();
        sessionManager.addMessage("ctx-test", "user", "继续分析");

        RetrievalService retrievalService = new StubRetrievalService() {
            @Override
            public List<StoredMessage> searchHistory(SearchQuery query) {
                return List.of(
                        new StoredMessage("h-tool", "ctx-test", 1, "tool", "旧工具输出", null, "tool-1", null, null, Map.of(), Instant.now()),
                        new StoredMessage("h-assistant", "ctx-test", 2, "assistant", "旧分析结论", null, null, null, null, Map.of(), Instant.now())
                );
            }
        };

        DefaultContextAssembler assembler = new DefaultContextAssembler(sessionManager, 10, retrievalService);

        List<Message> messages = assembler.assemble(
                "ctx-test",
                "继续分析",
                new ContextAssemblyOptions(10, 4, 3, 4, 4096)
        );

        assertFalse(messages.stream().anyMatch(message -> "tool".equals(message.getRole())));
        assertTrue(messages.stream().anyMatch(message -> "assistant".equals(message.getRole())
                && message.getContent().contains("旧分析结论")));
    }

    private static String repeat(String seed, int times) {
        return seed.repeat(Math.max(1, times));
    }

    private static class StubRetrievalService implements RetrievalService {
        @Override
        public List<StoredMessage> searchHistory(SearchQuery query) {
            return List.of(
                    new StoredMessage("h1", "ctx-test", 1, "assistant", repeat("retrieved history ", 80), null, null, null, null, Map.of(), Instant.now())
            );
        }

        @Override
        public List<ChunkSummary> searchSummaries(SearchQuery query) {
            return List.of(
                    new ChunkSummary("c1", "ctx-test", repeat("chunk summary ", 80), List.of(), List.of(), List.of(), List.of(), 1, Instant.now())
            );
        }

        @Override
        public List<MemoryFact> searchMemory(SearchQuery query) {
            return List.of(
                    new MemoryFact("f1", "ctx-test", "session", "constraint", "user", "requires", repeat("memory fact ", 60), Map.of(), 0.8, true, Instant.now(), Instant.now())
            );
        }

        @Override
        public Optional<SessionSummaryRecord> getSessionSummary(String sessionId) {
            return Optional.of(new SessionSummaryRecord(
                    sessionId,
                    repeat("session summary ", 100),
                    List.of(),
                    List.of(),
                    List.of(),
                    1,
                    1,
                    Instant.now()
            ));
        }

        @Override
        public RetrievalBundle retrieveForContext(String sessionId, String userInput) {
            return new RetrievalBundle(
                    searchHistory(null),
                    searchSummaries(null),
                    searchMemory(null),
                    getSessionSummary(sessionId)
            );
        }
    }
}
