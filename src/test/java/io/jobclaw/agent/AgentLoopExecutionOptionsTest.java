package io.jobclaw.agent;

import io.jobclaw.config.Config;
import io.jobclaw.context.ContextAssembler;
import io.jobclaw.context.ContextAssemblyPolicy;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.SummaryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AgentLoopExecutionOptionsTest {

    @Test
    void shouldApplyAgentDefinitionOverridesToExecutionOptions() throws Exception {
        Config config = Config.defaultConfig();
        config.getAgent().setProvider("ollama");
        config.getAgent().setModel("llama3.1");
        AgentLoop loop = new AgentLoop(
                config,
                new SessionManager(),
                new ToolCallback[0],
                mock(ContextBuilder.class),
                mock(ContextAssembler.class),
                mock(ContextAssemblyPolicy.class),
                mock(SummaryService.class)
        );

        AgentDefinition.AgentConfig agentConfig = new AgentDefinition.AgentConfig();
        agentConfig.setModel("custom-model");
        agentConfig.setTemperature(0.25);
        agentConfig.setMaxTokens(4096);

        AgentDefinition definition = AgentDefinition.builder()
                .code("reviewer")
                .displayName("Reviewer")
                .systemPrompt("prompt")
                .config(agentConfig)
                .build();

        Method method = AgentLoop.class.getDeclaredMethod("buildExecutionOptions", AgentDefinition.class, String.class);
        method.setAccessible(true);
        OpenAiChatOptions options = (OpenAiChatOptions) method.invoke(loop, definition, config.getAgent().getModel());

        assertEquals("custom-model", options.getModel());
        assertEquals(4096, options.getMaxTokens());
        assertEquals(0.25, options.getTemperature());
    }
}
