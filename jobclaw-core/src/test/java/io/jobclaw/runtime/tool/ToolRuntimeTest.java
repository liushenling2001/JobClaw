package io.jobclaw.runtime.tool;

import io.jobclaw.agent.ExecutionEvent;
import io.jobclaw.config.Config;
import io.jobclaw.context.result.FileResultStore;
import io.jobclaw.context.result.ResultStore;
import io.jobclaw.tools.ContextRefTool;
import io.jobclaw.providers.Message;
import io.jobclaw.session.SessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRuntimeTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPublishSuccessEventsAndFlushBufferedThink() {
        Config config = Config.defaultConfig();
        SessionManager sessionManager = new SessionManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultToolExecutionStateTracker tracker = new DefaultToolExecutionStateTracker();
        ToolRuntime toolRuntime = new ToolRuntime(config, sessionManager, executor, tracker);
        List<ExecutionEvent> events = new ArrayList<>();

        tracker.bufferThink("session-1", "buffered-think");

        ToolExecutionResult result = toolRuntime.execute(new ToolExecutionRequest(
                "session-1",
                "demo_tool",
                "{\"path\":\"a.txt\"}",
                new StaticToolCallback("demo_tool", "done"),
                events::add
        ));

        assertTrue(result.success());
        assertEquals("done", result.response());
        assertFalse(tracker.isExecuting("session-1"));
        assertEquals(List.of(
                ExecutionEvent.EventType.TOOL_START,
                ExecutionEvent.EventType.TOOL_END,
                ExecutionEvent.EventType.TOOL_OUTPUT,
                ExecutionEvent.EventType.THINK_STREAM
        ), events.stream().map(ExecutionEvent::getType).toList());

        executor.shutdownNow();
    }

    @Test
    void shouldOnlyTruncateToolOutputForDisplayEvents() {
        Config config = Config.defaultConfig();
        config.getAgent().setMaxToolOutputLength(10);
        SessionManager sessionManager = new SessionManager(tempDir.toString());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultToolExecutionStateTracker tracker = new DefaultToolExecutionStateTracker();
        ToolRuntime toolRuntime = new ToolRuntime(config, sessionManager, executor, tracker);
        List<ExecutionEvent> events = new ArrayList<>();
        String longResponse = "abcdefghijklmnopqrstuvwxyz";

        ToolExecutionResult result = toolRuntime.execute(new ToolExecutionRequest(
                "session-display-limit",
                "long_tool",
                "{}",
                new StaticToolCallback("long_tool", longResponse),
                events::add
        ));

        assertEquals(longResponse, result.response());
        List<Message> history = sessionManager.getHistory("session-display-limit");
        assertTrue(history.isEmpty());

        ExecutionEvent outputEvent = events.stream()
                .filter(event -> event.getType() == ExecutionEvent.EventType.TOOL_OUTPUT)
                .findFirst()
                .orElseThrow();
        assertTrue(outputEvent.getContent().startsWith("abcdefghij"));
        assertTrue(outputEvent.getContent().contains("返回结果已截断"));
        assertEquals(longResponse.length(), outputEvent.getMetadata().get("fullOutputLength"));

        executor.shutdownNow();
    }

    @Test
    void shouldStoreLargeToolOutputAsContextReferenceForModel() {
        Config config = Config.defaultConfig();
        config.getAgent().setContextRefEnabled(true);
        config.getAgent().setContextRefThresholdChars(20);
        config.getAgent().setContextRefPreviewChars(8);
        config.getAgent().setContextRefReadMaxChars(50);
        SessionManager sessionManager = new SessionManager(tempDir.resolve("sessions").toString());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultToolExecutionStateTracker tracker = new DefaultToolExecutionStateTracker();
        ResultStore resultStore = new FileResultStore(tempDir.resolve("results"), config.getAgent().getContextRefPreviewChars());
        ToolRuntime toolRuntime = new ToolRuntime(config, sessionManager, executor, tracker,
                new io.jobclaw.agent.completion.ActiveExecutionRegistry(), resultStore);
        List<ExecutionEvent> events = new ArrayList<>();
        String longResponse = "abcdefghijklmnopqrstuvwxyz";

        ToolExecutionResult result = toolRuntime.execute(new ToolExecutionRequest(
                "session-context-ref",
                "long_tool",
                "{}",
                new StaticToolCallback("long_tool", longResponse),
                events::add
        ));

        assertTrue(result.response().contains("Large tool result stored as a context reference."));
        assertTrue(result.response().contains("refId: ref-"));
        assertFalse(result.response().contains("ijklmnopqrstuvwxyz"));

        List<Message> history = sessionManager.getHistory("session-context-ref");
        assertTrue(history.isEmpty());

        String refId = result.response().lines()
                .filter(line -> line.startsWith("refId: "))
                .map(line -> line.substring("refId: ".length()).trim())
                .findFirst()
                .orElseThrow();
        ContextRefTool contextRefTool = new ContextRefTool(resultStore, config);
        String readResult = contextRefTool.contextRef("read", refId, null, "0", "50", null);
        assertTrue(readResult.contains(longResponse));

        ExecutionEvent outputEvent = events.stream()
                .filter(event -> event.getType() == ExecutionEvent.EventType.TOOL_OUTPUT)
                .findFirst()
                .orElseThrow();
        assertEquals(longResponse.length(), outputEvent.getMetadata().get("fullOutputLength"));

        executor.shutdownNow();
    }

    @Test
    void shouldStoreOverflowToolOutputAsContextReferenceForTurnBudget() {
        Config config = Config.defaultConfig();
        config.getAgent().setContextRefEnabled(true);
        config.getAgent().setContextRefThresholdChars(1_000);
        config.getAgent().setContextRefTurnBudgetChars(20);
        config.getAgent().setContextRefPreviewChars(8);
        SessionManager sessionManager = new SessionManager(tempDir.resolve("turn-budget-sessions").toString());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultToolExecutionStateTracker tracker = new DefaultToolExecutionStateTracker();
        ResultStore resultStore = new FileResultStore(tempDir.resolve("turn-budget-results"), config.getAgent().getContextRefPreviewChars());
        ToolRuntime toolRuntime = new ToolRuntime(config, sessionManager, executor, tracker,
                new io.jobclaw.agent.completion.ActiveExecutionRegistry(), resultStore);

        ToolExecutionResult first = toolRuntime.execute(new ToolExecutionRequest(
                "session-turn-budget",
                "read_file",
                "{\"path\":\"a.txt\"}",
                new StaticToolCallback("read_file", "abcdefghijklmnop"),
                event -> {}
        ));
        ToolExecutionResult second = toolRuntime.execute(new ToolExecutionRequest(
                "session-turn-budget",
                "search_files",
                "{\"query\":\"demo\"}",
                new StaticToolCallback("search_files", "qrstuvwxyz123456"),
                event -> {}
        ));

        assertEquals("abcdefghijklmnop", first.response());
        assertTrue(second.response().contains("Large tool result stored as a context reference."));
        assertTrue(second.response().contains("refId: ref-"));

        executor.shutdownNow();
    }

    @Test
    void shouldAllowRepeatedToolCallsWhenTheirResultsKeepChanging() {
        Config config = Config.defaultConfig();
        SessionManager sessionManager = new SessionManager(tempDir.resolve("duplicate-tool-sessions").toString());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultToolExecutionStateTracker tracker = new DefaultToolExecutionStateTracker();
        ToolRuntime toolRuntime = new ToolRuntime(config, sessionManager, executor, tracker);
        AtomicInteger calls = new AtomicInteger();
        ToolCallback callback = new CountingToolCallback("read_file", calls);

        ToolExecutionRequest request = new ToolExecutionRequest(
                "session-duplicate-read",
                "read_file",
                "{\"path\":\"same.txt\"}",
                callback,
                event -> {}
        );

        ToolExecutionResult first = toolRuntime.execute(request);
        ToolExecutionResult second = toolRuntime.execute(request);
        ToolExecutionResult third = toolRuntime.execute(request);
        ToolExecutionResult fourth = toolRuntime.execute(request);

        assertEquals("read-1", first.response());
        assertEquals("read-2", second.response());
        assertEquals("read-3", third.response());
        assertEquals("read-4", fourth.response());
        assertEquals(4, calls.get());

        executor.shutdownNow();
    }

    @Test
    void shouldBlockThirdStableRepeatedToolCallAndReusePreviousResult() {
        Config config = Config.defaultConfig();
        SessionManager sessionManager = new SessionManager(tempDir.resolve("stable-repeat-sessions").toString());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultToolExecutionStateTracker tracker = new DefaultToolExecutionStateTracker();
        ToolRuntime toolRuntime = new ToolRuntime(config, sessionManager, executor, tracker);
        AtomicInteger calls = new AtomicInteger();
        ToolCallback callback = new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name("read_file")
                    .description("test")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                calls.incrementAndGet();
                return "stable file content";
            }
        };

        ToolExecutionResult first = toolRuntime.execute(new ToolExecutionRequest(
                "session-stable-repeat",
                "read_file",
                "{\"path\":\"same.txt\",\"start\":0}",
                callback,
                event -> {}
        ));
        ToolExecutionResult second = toolRuntime.execute(new ToolExecutionRequest(
                "session-stable-repeat",
                "read_file",
                "{\"start\":0,\"path\":\"same.txt\"}",
                callback,
                event -> {}
        ));
        ToolExecutionResult third = toolRuntime.execute(new ToolExecutionRequest(
                "session-stable-repeat",
                "read_file",
                "{\"path\":\"same.txt\",\"start\":0}",
                callback,
                event -> {}
        ));

        assertEquals("stable file content", first.response());
        assertEquals("stable file content", second.response());
        assertTrue(third.response().contains("REPETITION_DETECTED"));
        assertTrue(third.response().contains("stable file content"));
        assertEquals(2, calls.get());

        executor.shutdownNow();
    }

    @Test
    void shouldAllowStableRepeatedCallsWhenGuardIsDisabled() {
        Config config = Config.defaultConfig();
        config.getAgent().setToolRepeatGuardEnabled(false);
        SessionManager sessionManager = new SessionManager(tempDir.resolve("repeat-disabled-sessions").toString());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultToolExecutionStateTracker tracker = new DefaultToolExecutionStateTracker();
        ToolRuntime toolRuntime = new ToolRuntime(config, sessionManager, executor, tracker);
        AtomicInteger calls = new AtomicInteger();
        ToolCallback callback = new StableCountingToolCallback("read_file", calls);
        ToolExecutionRequest request = new ToolExecutionRequest(
                "session-repeat-disabled",
                "read_file",
                "{\"path\":\"same.txt\"}",
                callback,
                event -> {}
        );

        toolRuntime.execute(request);
        toolRuntime.execute(request);
        toolRuntime.execute(request);

        assertEquals(3, calls.get());
        executor.shutdownNow();
    }

    @Test
    void shouldReturnToolExceptionAsModelVisibleErrorResultAndClearTrackerState() {
        Config config = Config.defaultConfig();
        SessionManager sessionManager = new SessionManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultToolExecutionStateTracker tracker = new DefaultToolExecutionStateTracker();
        ToolRuntime toolRuntime = new ToolRuntime(config, sessionManager, executor, tracker);
        List<ExecutionEvent> events = new ArrayList<>();

        ToolExecutionResult result = toolRuntime.execute(new ToolExecutionRequest(
                "session-2",
                "failing_tool",
                "{}",
                new StaticToolCallback("failing_tool", new RuntimeException("boom")),
                events::add
        ));

        assertFalse(result.success());
        assertEquals("Error: boom", result.response());
        assertFalse(tracker.isExecuting("session-2"));
        assertEquals(List.of(
                ExecutionEvent.EventType.TOOL_START,
                ExecutionEvent.EventType.TOOL_ERROR
        ), events.stream().map(ExecutionEvent::getType).toList());

        executor.shutdownNow();
    }

    @Test
    void shouldTreatErrorTextToolResponseAsToolErrorEventWithoutThrowing() {
        Config config = Config.defaultConfig();
        SessionManager sessionManager = new SessionManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultToolExecutionStateTracker tracker = new DefaultToolExecutionStateTracker();
        ToolRuntime toolRuntime = new ToolRuntime(config, sessionManager, executor, tracker);
        List<ExecutionEvent> events = new ArrayList<>();

        ToolExecutionResult result = toolRuntime.execute(new ToolExecutionRequest(
                "session-error-text",
                "run_command",
                "{}",
                new StaticToolCallback("run_command", "Error: Command exited with code 1"),
                events::add
        ));

        assertFalse(result.success());
        assertFalse(tracker.isExecuting("session-error-text"));
        assertEquals(List.of(
                ExecutionEvent.EventType.TOOL_START,
                ExecutionEvent.EventType.TOOL_ERROR
        ), events.stream().map(ExecutionEvent::getType).toList());

        executor.shutdownNow();
    }

    @Test
    void shouldLetSpawnToolOwnChildAgentTimeoutWithGrace() {
        Config config = Config.defaultConfig();
        config.getAgent().setChildAgentTimeoutMs(50);
        config.getAgent().setToolCallTimeoutSeconds(1);
        SessionManager sessionManager = new SessionManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultToolExecutionStateTracker tracker = new DefaultToolExecutionStateTracker();
        ToolRuntime toolRuntime = new ToolRuntime(config, sessionManager, executor, tracker);

        ToolExecutionResult result = toolRuntime.execute(new ToolExecutionRequest(
                "session-spawn",
                "spawn",
                "{\"timeoutMs\":50}",
                new SleepingToolCallback("spawn", "spawn handled timeout", 80),
                event -> {}
        ));

        assertEquals("spawn handled timeout", result.response());
        assertFalse(tracker.isExecuting("session-spawn"));

        executor.shutdownNow();
    }

    @Test
    void shouldNotLetSpawnRequestTimeoutReduceConfiguredChildAgentTimeout() {
        Config config = Config.defaultConfig();
        config.getAgent().setChildAgentTimeoutMs(100);
        config.getAgent().setToolCallTimeoutSeconds(1);
        SessionManager sessionManager = new SessionManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultToolExecutionStateTracker tracker = new DefaultToolExecutionStateTracker();
        ToolRuntime toolRuntime = new ToolRuntime(config, sessionManager, executor, tracker);

        ToolExecutionResult result = toolRuntime.execute(new ToolExecutionRequest(
                "session-spawn-floor",
                "spawn",
                "{\"timeoutMs\":1}",
                new SleepingToolCallback("spawn", "spawn used configured timeout floor", 20),
                event -> {}
        ));

        assertEquals("spawn used configured timeout floor", result.response());
        assertFalse(tracker.isExecuting("session-spawn-floor"));

        executor.shutdownNow();
    }

    @Test
    void shouldLetCommandToolTimeoutExtendOuterRuntimeTimeout() {
        Config config = Config.defaultConfig();
        config.getAgent().setToolCallTimeoutSeconds(1);
        SessionManager sessionManager = new SessionManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultToolExecutionStateTracker tracker = new DefaultToolExecutionStateTracker();
        ToolRuntime toolRuntime = new ToolRuntime(config, sessionManager, executor, tracker);

        ToolExecutionResult result = toolRuntime.execute(new ToolExecutionRequest(
                "session-run-command-timeout",
                "run_command",
                "{\"command\":\"demo\",\"timeout\":2}",
                new SleepingToolCallback("run_command", "command completed", 1_200),
                event -> {}
        ));

        assertEquals("command completed", result.response());
        assertFalse(tracker.isExecuting("session-run-command-timeout"));

        executor.shutdownNow();
    }

    private static class StaticToolCallback implements ToolCallback {
        private final ToolDefinition toolDefinition;
        private final String response;
        private final RuntimeException runtimeException;

        private StaticToolCallback(String toolName, String response) {
            this.toolDefinition = ToolDefinition.builder()
                    .name(toolName)
                    .description("test")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
            this.response = response;
            this.runtimeException = null;
        }

        private StaticToolCallback(String toolName, RuntimeException runtimeException) {
            this.toolDefinition = ToolDefinition.builder()
                    .name(toolName)
                    .description("test")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
            this.response = null;
            this.runtimeException = runtimeException;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public String call(String toolInput) {
            if (runtimeException != null) {
                throw runtimeException;
            }
            return response;
        }
    }

    private static class CountingToolCallback implements ToolCallback {
        private final ToolDefinition toolDefinition;
        private final AtomicInteger calls;

        private CountingToolCallback(String toolName, AtomicInteger calls) {
            this.toolDefinition = ToolDefinition.builder()
                    .name(toolName)
                    .description("test")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
            this.calls = calls;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public String call(String toolInput) {
            return "read-" + calls.incrementAndGet();
        }
    }

    private static class StableCountingToolCallback implements ToolCallback {
        private final ToolDefinition toolDefinition;
        private final AtomicInteger calls;

        private StableCountingToolCallback(String toolName, AtomicInteger calls) {
            this.toolDefinition = ToolDefinition.builder()
                    .name(toolName)
                    .description("test")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
            this.calls = calls;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public String call(String toolInput) {
            calls.incrementAndGet();
            return "stable";
        }
    }

    private static class SleepingToolCallback implements ToolCallback {
        private final ToolDefinition toolDefinition;
        private final String response;
        private final long sleepMillis;

        private SleepingToolCallback(String toolName, String response, long sleepMillis) {
            this.toolDefinition = ToolDefinition.builder()
                    .name(toolName)
                    .description("test")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
            this.response = response;
            this.sleepMillis = sleepMillis;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public String call(String toolInput) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted", e);
            }
            return response;
        }
    }
}
