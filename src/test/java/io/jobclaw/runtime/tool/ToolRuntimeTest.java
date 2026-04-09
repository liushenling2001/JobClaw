package io.jobclaw.runtime.tool;

import io.jobclaw.agent.ExecutionEvent;
import io.jobclaw.config.Config;
import io.jobclaw.session.SessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRuntimeTest {

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
    void shouldPublishErrorEventAndClearTrackerStateOnFailure() {
        Config config = Config.defaultConfig();
        SessionManager sessionManager = new SessionManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultToolExecutionStateTracker tracker = new DefaultToolExecutionStateTracker();
        ToolRuntime toolRuntime = new ToolRuntime(config, sessionManager, executor, tracker);
        List<ExecutionEvent> events = new ArrayList<>();

        RuntimeException exception = assertThrows(RuntimeException.class, () -> toolRuntime.execute(new ToolExecutionRequest(
                "session-2",
                "failing_tool",
                "{}",
                new StaticToolCallback("failing_tool", new RuntimeException("boom")),
                events::add
        )));

        assertEquals("boom", exception.getMessage());
        assertFalse(tracker.isExecuting("session-2"));
        assertEquals(List.of(
                ExecutionEvent.EventType.TOOL_START,
                ExecutionEvent.EventType.TOOL_ERROR
        ), events.stream().map(ExecutionEvent::getType).toList());

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
}
