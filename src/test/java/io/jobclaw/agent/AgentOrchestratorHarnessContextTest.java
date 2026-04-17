package io.jobclaw.agent;

import io.jobclaw.agent.planning.TaskPlanningPolicy;
import io.jobclaw.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentOrchestratorHarnessContextTest {

    @AfterEach
    void tearDown() {
        AgentExecutionContext.clear();
    }

    @Test
    void shouldExposeHarnessRunIdInsideAgentExecutionContext() {
        AgentRegistry registry = mock(AgentRegistry.class);
        TaskHarnessService harnessService = new TaskHarnessService();
        TaskHarnessVerifier verifier = new CompositeTaskHarnessVerifier(java.util.List.of(new DefaultTaskHarnessVerifier()));
        TaskHarnessRepairPromptBuilder repairPromptBuilder = new TaskHarnessRepairPromptBuilder();
        TaskHarnessRepairStrategy repairStrategy = new TaskHarnessRepairStrategy(Config.defaultConfig());
        AgentLoop loop = mock(AgentLoop.class);
        AtomicReference<String> observedRunId = new AtomicReference<>();

        when(registry.getOrCreateAgent(any(AgentRole.class), anyString())).thenReturn(loop);
        when(loop.process(anyString(), anyString(), any(java.util.function.Consumer.class)))
                .thenAnswer(invocation -> {
                    observedRunId.set(AgentExecutionContext.getCurrentRunId());
                    return "done";
                });

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                Config.defaultConfig(),
                registry,
                harnessService,
                verifier,
                repairPromptBuilder,
                repairStrategy,
                new TaskPlanningPolicy()
        );

        String result = orchestrator.process("session-a", "hello", event -> {});

        assertEquals("done", result);
        assertNotNull(observedRunId.get());
        TaskHarnessRun run = harnessService.getRun(observedRunId.get());
        assertNotNull(run);
        assertEquals("session-a", run.getSessionId());
    }
}
