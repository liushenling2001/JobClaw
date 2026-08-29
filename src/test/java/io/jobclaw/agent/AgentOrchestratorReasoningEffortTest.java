package io.jobclaw.agent;

import io.jobclaw.config.Config;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOrchestratorReasoningEffortTest {

    @Test
    void shouldForwardConversationReasoningEffortToAgentLoop() {
        AgentRegistry registry = mock(AgentRegistry.class);
        AgentLoop loop = mock(AgentLoop.class);
        Consumer<ExecutionEvent> callback = event -> { };
        when(registry.getOrCreateAgent(AgentRole.ASSISTANT, "web:test")).thenReturn(loop);

        AgentOrchestrator orchestrator = new AgentOrchestrator(Config.defaultConfig(), registry);
        orchestrator.process("web:test", "你好", callback, "high");

        verify(loop).processWithDefinition("web:test", "你好", null, callback, "high");
    }
}
