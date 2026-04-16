package io.jobclaw.runtime.tool;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.config.Config;
import io.jobclaw.providers.Message;
import io.jobclaw.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolRuntimeContextPropagationTest {

    @AfterEach
    void tearDown() {
        AgentExecutionContext.clear();
    }

    @Test
    void shouldPropagateExecutionContextIntoToolThread() {
        Config config = Config.defaultConfig();
        SessionManager sessionManager = new SessionManager();
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            ToolExecutionStateTracker stateTracker = new DefaultToolExecutionStateTracker();
            ToolRuntime runtime = new ToolRuntime(config, sessionManager, executorService, stateTracker);
            AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                    "session-a",
                    null,
                    "run-harness-1",
                    null,
                    "assistant",
                    "Assistant"
            ));

            ToolCallback callback = new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder()
                            .name("context_probe")
                            .description("probe")
                            .inputSchema("{}")
                            .build();
                }

                @Override
                public String call(String toolInput) {
                    return AgentExecutionContext.getCurrentRunId();
                }
            };

            ToolExecutionResult result = runtime.execute(new ToolExecutionRequest(
                    "session-a",
                    "context_probe",
                    "{}",
                    callback,
                    null
            ));

            assertEquals("run-harness-1", result.response());
        } finally {
            executorService.shutdownNow();
        }
    }
}
