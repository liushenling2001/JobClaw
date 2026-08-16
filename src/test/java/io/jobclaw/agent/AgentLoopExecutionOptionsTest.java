package io.jobclaw.agent;

import io.jobclaw.config.Config;
import io.jobclaw.context.ContextAssembler;
import io.jobclaw.context.ContextAssemblyPolicy;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.SummaryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        OpenAiChatOptions options = buildOpenAiOptions(method.invoke(loop, definition, config.getAgent().getModel()));

        assertEquals("custom-model", options.getModel());
        assertEquals(4096, options.getMaxTokens());
        assertEquals(0.25, options.getTemperature());
    }

    @Test
    void shouldUseConfiguredLlmCallTimeoutForOpenAiCallClient() throws Exception {
        Config config = Config.defaultConfig();
        config.getAgent().setProvider("ollama");
        config.getAgent().setModel("llama3.1");
        config.getAgent().setLlmCallTimeoutSeconds(240);
        AgentLoop loop = new AgentLoop(
                config,
                new SessionManager(),
                new ToolCallback[0],
                mock(ContextBuilder.class),
                mock(ContextAssembler.class),
                mock(ContextAssemblyPolicy.class),
                mock(SummaryService.class)
        );

        Method method = AgentLoop.class.getDeclaredMethod("safeTimeoutMillis", int.class, int.class);
        method.setAccessible(true);

        assertEquals(240_000, method.invoke(loop, config.getAgent().getLlmCallTimeoutSeconds(), 300));
    }

    @Test
    void shouldUseNativeDeepSeekOptionsForDeepSeekProvider() throws Exception {
        Config config = Config.defaultConfig();
        config.getAgent().setProvider("deepseek");
        config.getAgent().setModel("deepseek-v4-flash");
        config.getProviders().getDeepseek().setApiKey("sk-test");
        AgentLoop loop = new AgentLoop(
                config,
                new SessionManager(),
                new ToolCallback[0],
                mock(ContextBuilder.class),
                mock(ContextAssembler.class),
                mock(ContextAssemblyPolicy.class),
                mock(SummaryService.class)
        );

        Method method = AgentLoop.class.getDeclaredMethod("buildExecutionOptions",
                AgentDefinition.class, String.class, String.class);
        method.setAccessible(true);

        DeepSeekChatOptions options = buildDeepSeekOptions(method.invoke(loop, null, "deepseek-v4-flash", "deepseek"));

        assertEquals("deepseek-v4-flash", options.getModel());
    }

    @Test
    void shouldApplyQwenThinkingModeToOpenAiCompatibleRequest() throws Exception {
        Config config = Config.defaultConfig();
        config.getAgent().setProvider("openrouter");
        config.getAgent().setModel("qwen");
        config.getAgent().setThinkingMode("auto");
        AgentLoop loop = new AgentLoop(
                config,
                new SessionManager(),
                new ToolCallback[0],
                mock(ContextBuilder.class),
                mock(ContextAssembler.class),
                mock(ContextAssemblyPolicy.class),
                mock(SummaryService.class)
        );

        Method method = AgentLoop.class.getDeclaredMethod("buildExecutionOptions",
                AgentDefinition.class, String.class, String.class, String.class);
        method.setAccessible(true);

        OpenAiChatOptions options = buildOpenAiOptions(method.invoke(
                loop,
                null,
                "qwen",
                "openrouter",
                "http://100.113.233.0:8000/v1"
        ));

        assertEquals(
                Map.of("chat_template_kwargs", Map.of("enable_thinking", false)),
                options.getExtraBody()
        );
    }

    @Test
    void shouldCreateNativeDeepSeekChatModelForDeepSeekProvider() throws Exception {
        Config config = Config.defaultConfig();
        config.getAgent().setProvider("deepseek");
        config.getAgent().setModel("deepseek-reasoner");
        config.getProviders().getDeepseek().setApiKey("sk-test");
        AgentLoop loop = new AgentLoop(
                config,
                new SessionManager(),
                new ToolCallback[0],
                mock(ContextBuilder.class),
                mock(ContextAssembler.class),
                mock(ContextAssemblyPolicy.class),
                mock(SummaryService.class)
        );

        Method method = AgentLoop.class.getDeclaredMethod("createChatModel",
                io.jobclaw.runtime.provider.ResolvedProviderConfig.class);
        method.setAccessible(true);

        Object chatModel = method.invoke(loop, new io.jobclaw.runtime.provider.ResolvedProviderConfig(
                "deepseek",
                "deepseek-reasoner",
                "sk-test",
                "https://api.deepseek.com/v1",
                "https://api.deepseek.com/v1",
                true,
                false
        ));

        assertInstanceOf(DeepSeekChatModel.class, chatModel);
    }

    @Test
    void shouldStripOpenAiCompatibleV1SuffixForNativeDeepSeekBaseUrl() throws Exception {
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

        Method method = AgentLoop.class.getDeclaredMethod("nativeDeepSeekBaseUrl", String.class);
        method.setAccessible(true);

        assertEquals("https://api.deepseek.com", method.invoke(loop, "https://api.deepseek.com/v1"));
    }

    private OpenAiChatOptions buildOpenAiOptions(Object value) {
        if (value instanceof OpenAiChatOptions options) {
            return options;
        }
        if (value instanceof OpenAiChatOptions.Builder builder) {
            return builder.build();
        }
        throw new IllegalArgumentException("Unexpected options type: " + value);
    }

    private DeepSeekChatOptions buildDeepSeekOptions(Object value) {
        if (value instanceof DeepSeekChatOptions options) {
            return options;
        }
        if (value instanceof DeepSeekChatOptions.Builder builder) {
            return builder.build();
        }
        throw new IllegalArgumentException("Unexpected options type: " + value);
    }
}
