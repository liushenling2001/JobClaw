package io.jobclaw.config;

import io.jobclaw.agent.AgentLoop;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.providers.HTTPProvider;
import io.jobclaw.providers.LLMProvider;
import io.jobclaw.session.SessionManager;
import io.jobclaw.tools.FileTools;
import io.jobclaw.tools.ToolRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Spring configuration for Agent-related beans
 * Ensures consistent initialization of LLMProvider, AgentLoop, and related components
 */
@Configuration
public class AgentBeansConfig {

    @Bean
    @ConditionalOnMissingBean
    public Config config() throws IOException {
        return ConfigLoader.load();
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageBus messageBus() {
        return new MessageBus();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry() {
        return new ToolRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionManager sessionManager(Config config) {
        return new SessionManager(config.getWorkspacePath());
    }

    @Bean
    @ConditionalOnMissingBean
    public LLMProvider llmProvider(Config config) {
        // Get provider config from agent's configured provider
        String providerName = config.getAgent().getProvider();

        // Fallback: try to get provider from model definition
        if (providerName == null || providerName.isEmpty()) {
            var modelDef = config.getModels().getDefinitions().get(config.getAgent().getModel());
            if (modelDef != null && modelDef.getProvider() != null) {
                providerName = modelDef.getProvider();
            }
        }

        // Fallback: use first available provider
        if (providerName == null || providerName.isEmpty()) {
            var firstProvider = config.getProviders().getFirstAvailableProvider()
                    .orElseThrow(() -> new IllegalStateException("未配置任何可用的 Provider"));
            providerName = firstProvider.name;
        }

        // Get provider config
        io.jobclaw.config.ProvidersConfig.ProviderConfig providerConfig = getProviderConfig(
                config.getProviders(), providerName);

        if (providerConfig == null) {
            throw new IllegalStateException("Provider '" + providerName + "' 未找到配置");
        }

        // Get API key and base URL
        String apiKey = providerConfig.getApiKey();
        if (apiKey == null) apiKey = ""; // ollama doesn't need apiKey

        String apiBase = resolveApiBase(
                providerConfig.getApiBase(),
                ProvidersConfig.getDefaultApiBase(providerName)
        );

        String model = config.getAgent().getModel();

        return new HTTPProvider(apiKey, apiBase, model);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentLoop agentLoop(Config config, SessionManager sessionManager, FileTools fileTools) {
        return new AgentLoop(config, sessionManager, fileTools);
    }

    /**
     * Get provider config by name
     */
    private io.jobclaw.config.ProvidersConfig.ProviderConfig getProviderConfig(
            io.jobclaw.config.ProvidersConfig providers, String name) {
        return switch (name) {
            case "openrouter" -> providers.getOpenrouter();
            case "anthropic" -> providers.getAnthropic();
            case "openai" -> providers.getOpenai();
            case "zhipu" -> providers.getZhipu();
            case "gemini" -> providers.getGemini();
            case "dashscope" -> providers.getDashscope();
            case "ollama" -> providers.getOllama();
            default -> null;
        };
    }

    /**
     * Resolve API Base URL, use default if not configured
     */
    private String resolveApiBase(String configuredBase, String defaultBase) {
        if (configuredBase == null || configuredBase.isEmpty()) {
            return defaultBase;
        }
        return configuredBase;
    }
}
