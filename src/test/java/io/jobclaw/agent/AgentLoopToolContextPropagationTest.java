package io.jobclaw.agent;

import io.jobclaw.config.Config;
import io.jobclaw.context.ContextAssembler;
import io.jobclaw.context.ContextAssemblyPolicy;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.SummaryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AgentLoopToolContextPropagationTest {

    @AfterEach
    void tearDown() {
        AgentExecutionContext.clear();
    }

    @Test
    void wrappedToolCallbackShouldRestoreExecutionContextOnCallingThread() throws Exception {
        AgentLoop agentLoop = new AgentLoop(
                Config.defaultConfig(),
                new SessionManager(),
                new ToolCallback[0],
                mock(ContextBuilder.class),
                mock(ContextAssembler.class),
                mock(ContextAssemblyPolicy.class),
                mock(SummaryService.class)
        );
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "session-a",
                null,
                "run-harness-2",
                null,
                "assistant",
                "Assistant",
                null
        ));

        ToolCallback raw = new ToolCallback() {
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

        Method method = AgentLoop.class.getDeclaredMethod(
                "wrapSingleCallback",
                ToolCallback.class,
                String.class,
                java.util.function.Consumer.class
        );
        method.setAccessible(true);
        ToolCallback wrapped = (ToolCallback) method.invoke(agentLoop, raw, "session-a", null);

        String observed = CompletableFuture.supplyAsync(() -> wrapped.call("{}")).join();

        assertEquals("run-harness-2", observed);
    }
}
