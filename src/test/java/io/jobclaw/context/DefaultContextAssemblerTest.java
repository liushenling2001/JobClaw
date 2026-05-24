package io.jobclaw.context;

import io.jobclaw.agent.experience.ExperienceMemory;
import io.jobclaw.agent.experience.ExperienceMemoryRetriever;
import io.jobclaw.agent.experience.ExperienceMemoryService;
import io.jobclaw.agent.experience.ExperienceMemoryStore;
import io.jobclaw.agent.experience.ExperienceMemoryType;
import io.jobclaw.conversation.StoredMessage;
import io.jobclaw.config.ExperienceConfig;
import io.jobclaw.providers.Message;
import io.jobclaw.providers.ToolCall;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultContextAssemblerTest {

    @Test
    void trimsContextWhenPromptBudgetIsSmall() {
        SessionManager sessionManager = new SessionManager();
        String sessionId = "ctx-small-budget";
        sessionManager.deleteSession(sessionId);
        sessionManager.addMessage(sessionId, "user", repeat("recent user message ", 60));
        sessionManager.addMessage(sessionId, "assistant", repeat("recent assistant message ", 60));

        RetrievalService retrievalService = new StubRetrievalService();
        DefaultContextAssembler assembler = new DefaultContextAssembler(sessionManager, 10, retrievalService);

        List<Message> messages = assembler.assemble(
                sessionId,
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
    void shouldPreserveRecentToolCallPairs() {
        SessionManager sessionManager = new SessionManager();
        String sessionId = "ctx-tool-pair";
        sessionManager.deleteSession(sessionId);
        sessionManager.addMessage(sessionId, "user", "请分析 excel");
        Message assistantToolCall = Message.assistant("");
        assistantToolCall.setToolCalls(List.of(new ToolCall("tool-1", "read_file", "{\"path\":\"sales.xlsx\"}")));
        sessionManager.addFullMessage(sessionId, assistantToolCall);
        sessionManager.addFullMessage(sessionId, Message.tool("tool-1", "A1:B999 的原始表格输出"));
        sessionManager.addMessage(sessionId, "assistant", "分析结果：销量最高的是三月");

        DefaultContextAssembler assembler = new DefaultContextAssembler(sessionManager, 10, new StubRetrievalService());

        List<Message> messages = assembler.assemble(
                sessionId,
                "继续总结",
                new ContextAssemblyOptions(10, 4, 3, 4, 4096)
        );

        assertEquals(1, messages.stream().filter(message -> "tool".equals(message.getRole())).count());
        assertTrue(messages.stream().anyMatch(message -> "assistant".equals(message.getRole())
                && message.getToolCalls() != null
                && !message.getToolCalls().isEmpty()));
        assertTrue(messages.stream().anyMatch(message -> "assistant".equals(message.getRole())
                && message.getContent().contains("分析结果")));
    }

    @Test
    void shouldExcludeToolMessagesFromRetrievedHistory() {
        SessionManager sessionManager = new SessionManager();
        String sessionId = "ctx-retrieved-tool";
        sessionManager.deleteSession(sessionId);
        sessionManager.addMessage(sessionId, "user", "继续分析");

        RetrievalService retrievalService = new StubRetrievalService() {
            @Override
            public List<StoredMessage> searchHistory(SearchQuery query) {
                return List.of(
                        new StoredMessage("h-tool", sessionId, 1, "tool", "旧工具输出", null, "tool-1", null, null, Map.of(), Instant.now()),
                        new StoredMessage("h-assistant", sessionId, 2, "assistant", "旧分析结论", null, null, null, null, Map.of(), Instant.now())
                );
            }
        };

        DefaultContextAssembler assembler = new DefaultContextAssembler(sessionManager, 10, retrievalService);

        List<Message> messages = assembler.assemble(
                sessionId,
                "继续分析",
                new ContextAssemblyOptions(10, 4, 3, 4, 4096, false)
        );

        assertFalse(messages.stream().anyMatch(message -> "tool".equals(message.getRole())));
        assertTrue(messages.stream().anyMatch(message -> "assistant".equals(message.getRole())
                && message.getContent().contains("旧分析结论")));
    }

    @Test
    void shouldIsolateRetrievedHistoryAndStatefulSummariesByDefault() {
        SessionManager sessionManager = new SessionManager();
        String sessionId = "ctx-isolate-state";
        sessionManager.deleteSession(sessionId);
        sessionManager.addMessage(sessionId, "user", "当前任务：处理 D:\\new\\input");

        RetrievalService retrievalService = new StubRetrievalService() {
            @Override
            public List<StoredMessage> searchHistory(SearchQuery query) {
                return List.of(
                        new StoredMessage("h-old", sessionId, 1, "assistant",
                                "旧任务路径 D:\\old\\input，manifestId=old-mf", null, null, null, null, Map.of(), Instant.now())
                );
            }

            @Override
            public List<ChunkSummary> searchSummaries(SearchQuery query) {
                return List.of(
                        new ChunkSummary("c-old", sessionId,
                                "旧任务 artifactPath=D:\\old\\result.xlsx pending=0 done=20",
                                List.of(), List.of(), List.of(), List.of(), 1, Instant.now())
                );
            }

            @Override
            public Optional<SessionSummaryRecord> getSessionSummary(String sessionId) {
                return Optional.of(new SessionSummaryRecord(
                        sessionId,
                        "旧任务 inputDir=D:\\old\\input manifestId=old-mf done=20",
                        List.of(),
                        List.of(),
                        List.of(),
                        1,
                        1,
                        Instant.now()
                ));
            }
        };

        DefaultContextAssembler assembler = new DefaultContextAssembler(sessionManager, 10, retrievalService);

        List<Message> messages = assembler.assemble(
                sessionId,
                "当前任务：处理 D:\\new\\input",
                new ContextAssemblyOptions(10, 4, 3, 4, 4096)
        );

        String assembled = messages.stream()
                .map(Message::getContent)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);

        assertFalse(assembled.contains("D:\\old\\input"));
        assertFalse(assembled.contains("old-mf"));
        assertFalse(assembled.contains("D:\\old\\result.xlsx"));
        assertTrue(assembled.contains("Historical session summary omitted"));
    }

    @Test
    void shouldInjectSanitizedOperatingExperienceWithoutHistoricalTargets() {
        SessionManager sessionManager = new SessionManager();
        String sessionId = "ctx-experience";
        sessionManager.deleteSession(sessionId);
        sessionManager.addMessage(sessionId, "user", "清理这个文件夹 D:\\new\\target");

        ExperienceMemory memory = new ExperienceMemory();
        memory.setId("exp-1");
        memory.setType(ExperienceMemoryType.WORKFLOW_EXPERIENCE);
        memory.setTitle("folder cleanup");
        memory.setApplicability("清理文件夹");
        memory.setMethodGuidance("先确认当前目标路径，再 dry-run 列出待删除项，不要复用 D:\\old\\folder 或 manifestId=old-mf");
        memory.setConfidence(0.9);

        ExperienceMemoryStore store = new InMemoryExperienceMemoryStore(List.of(memory));
        ExperienceConfig experienceConfig = new ExperienceConfig();
        ExperienceMemoryRetriever retriever = new ExperienceMemoryRetriever(
                new ExperienceMemoryService(store),
                experienceConfig
        );
        DefaultContextAssembler assembler = new DefaultContextAssembler(
                sessionManager,
                10,
                new StubRetrievalService(),
                retriever
        );

        List<Message> messages = assembler.assemble(
                sessionId,
                "清理这个文件夹 D:\\new\\target",
                new ContextAssemblyOptions(10, 4, 3, 4, 4096)
        );

        String assembled = messages.stream()
                .map(Message::getContent)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);

        assertTrue(assembled.contains("Relevant operating experience"));
        assertTrue(assembled.contains("Use experience only as process guidance"));
        assertFalse(assembled.contains("D:\\old\\folder"));
        assertFalse(assembled.contains("old-mf"));
    }

    @Test
    void shouldNotInjectExperienceWhenTaskPatternDoesNotMatch() {
        SessionManager sessionManager = new SessionManager();
        String sessionId = "ctx-experience-mismatch";
        sessionManager.deleteSession(sessionId);
        sessionManager.addMessage(sessionId, "user", "清理这个文件夹");

        ExperienceMemory memory = new ExperienceMemory();
        memory.setId("exp-github");
        memory.setType(ExperienceMemoryType.WORKFLOW_EXPERIENCE);
        memory.setTitle("github issue cleanup");
        memory.setTaskPattern("github");
        memory.getMetadata().put("objectType", "issue");
        memory.setMethodGuidance("整理 GitHub issue 时先按 label 分组。");
        memory.setConfidence(0.95);

        ExperienceMemoryRetriever retriever = new ExperienceMemoryRetriever(
                new ExperienceMemoryService(new InMemoryExperienceMemoryStore(List.of(memory))),
                new ExperienceConfig()
        );
        DefaultContextAssembler assembler = new DefaultContextAssembler(
                sessionManager,
                10,
                new StubRetrievalService(),
                retriever
        );

        List<Message> messages = assembler.assemble(
                sessionId,
                "清理这个文件夹",
                new ContextAssemblyOptions(10, 4, 3, 4, 4096)
        );

        String assembled = messages.stream()
                .map(Message::getContent)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);

        assertFalse(assembled.contains("Relevant operating experience"));
        assertFalse(assembled.contains("GitHub issue"));
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

    private static class InMemoryExperienceMemoryStore implements ExperienceMemoryStore {
        private List<ExperienceMemory> memories;

        private InMemoryExperienceMemoryStore(List<ExperienceMemory> memories) {
            this.memories = memories;
        }

        @Override
        public List<ExperienceMemory> list() {
            return memories;
        }

        @Override
        public void saveAll(List<ExperienceMemory> memories) {
            this.memories = memories;
        }
    }
}
