package io.jobclaw.config;

import io.jobclaw.agent.AgentLoop;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.channels.ChannelManager;
import io.jobclaw.cron.CronService;
import io.jobclaw.mcp.MCPService;
import io.jobclaw.providers.HTTPProvider;
import io.jobclaw.providers.LLMProvider;
import io.jobclaw.session.SessionManager;
import io.jobclaw.skills.SkillsLoader;
import io.jobclaw.skills.SkillsService;
import io.jobclaw.stats.TokenUsageService;
import io.jobclaw.tools.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
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
    public SkillsLoader skillsLoader(Config config) {
        return new SkillsLoader(config.getWorkspacePath(), null, null);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillsService skillsService(Config config, ToolsConfig toolsConfig, SkillsLoader skillsLoader) {
        SkillsService service = new SkillsService(config, toolsConfig);
        service.init();
        return service;
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
    public CronService cronService() {
        return new CronService();
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenUsageService tokenUsageService() {
        return new TokenUsageService();
    }

    @Bean
    @ConditionalOnMissingBean
    public MCPService mcpService() {
        return new MCPService();
    }

    @Bean
    public ToolCallback[] allToolCallbacks(
            FileTools fileTools,
            RunCommandTool runCommandTool,
            SkillsTools skillsTools,
            MessageTool messageTool,
            CronTool cronTool,
            MCPTool mcpTool,
            TokenUsageTool tokenUsageTool,
            WebSearchTool webSearchTool,
            WebFetchTool webFetchTool,
            ExecTool execTool) {

        // 注意：不包含 SpawnTool，因为它依赖 AgentOrchestrator，会形成循环依赖
        // 如果需要 SpawnTool，需要重构 AgentOrchestrator 或使用 @Lazy 注入

        return MethodToolCallbackProvider.builder()
                .toolObjects(fileTools, runCommandTool, skillsTools, messageTool, cronTool,
                            mcpTool, tokenUsageTool, webSearchTool, webFetchTool, execTool)
                .build()
                .getToolCallbacks();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentLoop agentLoop(Config config, SessionManager sessionManager,
                               ToolCallback[] allToolCallbacks) {
        return new AgentLoop(config, sessionManager, allToolCallbacks);
    }

    // Tool beans with dependencies
    @Bean
    public CronTool cronTool(CronService cronService) {
        return new CronTool(cronService);
    }

    @Bean
    public MessageTool messageTool(ChannelManager channelManager) {
        return new MessageTool(channelManager);
    }

    @Bean
    public TokenUsageTool tokenUsageTool(TokenUsageService tokenUsageService) {
        return new TokenUsageTool(tokenUsageService);
    }

    @Bean
    public MCPTool mcpTool(MCPService mcpService) {
        return new MCPTool(mcpService);
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
