package io.jobclaw.config;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Optional;

/**
 * JobClaw 主配置类
 * 基于 Spring Boot 3.3 + Java 17
 * 注意：此类由 AgentBeansConfig 中的 @Bean 方法从配置文件加载，不使用 @Component 自动扫描
 */
public class Config {

    private ModelsConfig models;
    private AgentConfig agent;
    private ChannelsConfig channels;
    private ProvidersConfig providers;
    private GatewayConfig gateway;
    private ToolsConfig tools;
    private SocialNetworkConfig socialNetwork;
    private MCPServersConfig mcpServers;

    public Config() {
        this.models = new ModelsConfig();
        this.agent = new AgentConfig();
        this.channels = new ChannelsConfig();
        this.providers = new ProvidersConfig();
        this.gateway = new GatewayConfig();
        this.tools = new ToolsConfig();
        this.socialNetwork = new SocialNetworkConfig();
        this.mcpServers = new MCPServersConfig();
    }

    public ModelsConfig getModels() { return models; }
    public void setModels(ModelsConfig models) { this.models = models; }

    public AgentConfig getAgent() { return agent; }
    public void setAgent(AgentConfig agent) { this.agent = agent; }

    public ChannelsConfig getChannels() { return channels; }
    public void setChannels(ChannelsConfig channels) { this.channels = channels; }

    public ProvidersConfig getProviders() { return providers; }
    public void setProviders(ProvidersConfig providers) { this.providers = providers; }

    public GatewayConfig getGateway() { return gateway; }
    public void setGateway(GatewayConfig gateway) { this.gateway = gateway; }

    public ToolsConfig getTools() { return tools; }
    public void setTools(ToolsConfig tools) { this.tools = tools; }

    public SocialNetworkConfig getSocialNetwork() { return socialNetwork; }
    public void setSocialNetwork(SocialNetworkConfig socialNetwork) { this.socialNetwork = socialNetwork; }

    public MCPServersConfig getMcpServers() {
        if (mcpServers == null) {
            mcpServers = new MCPServersConfig();
        }
        return mcpServers;
    }
    public void setMcpServers(MCPServersConfig mcpServers) { this.mcpServers = mcpServers; }

    @JsonIgnore
    public String getWorkspacePath() {
        return ConfigLoader.expandHome(agent.getWorkspace());
    }

    @JsonIgnore
    public String getApiKey() {
        return providers.getFirstValidProvider()
                .map(ProvidersConfig.ProviderConfig::getApiKey)
                .orElse("");
    }

    @JsonIgnore
    public String getApiBase() {
        return providers.getFirstValidProvider()
                .map(ProvidersConfig.ProviderConfig::getApiBase)
                .orElse("");
    }

    @JsonIgnore
    public Optional<String> validate() {
        // 检查工作空间
        if (!isValidWorkspace()) {
            return Optional.of("工作空间路径未配置 (agent.workspace)");
        }
        
        // 检查 LLM Provider 配置
        String validationError = validateProviders();
        if (validationError != null) {
            return Optional.of(validationError);
        }
        
        // 检查网关配置
        if (gateway != null) {
            int port = gateway.getPort();
            if (port < 1 || port > 65535) {
                return Optional.of("网关端口必须在 1-65535 之间，当前值：" + port);
            }
        }
        
        return Optional.empty();
    }

    /**
     * 验证 LLM Provider 配置
     * @return 错误信息，如果没有错误返回 null
     */
    @JsonIgnore
    private String validateProviders() {
        if (providers == null) {
            return "providers 配置为空，需要至少配置一个 LLM Provider";
        }
        
        // 检查选中的 provider
        String selectedProvider = agent != null ? agent.getProvider() : null;
        if (selectedProvider == null || selectedProvider.isEmpty()) {
            return "未指定默认 Provider (agent.provider)";
        }
        
        // 根据选中的 provider 检查对应的配置
        ProvidersConfig.ProviderConfig providerConfig = getProviderConfigByName(selectedProvider);
        if (providerConfig == null) {
            return "Provider '" + selectedProvider + "' 未在 providers 中配置";
        }
        
        if (providerConfig.getApiKey() == null || providerConfig.getApiKey().trim().isEmpty()) {
            return "Provider '" + selectedProvider + "' 的 API Key 未配置";
        }
        
        // 验证 API Key 格式（基本检查）
        String apiKey = providerConfig.getApiKey().trim();
        if (selectedProvider.equals("dashscope") && !apiKey.startsWith("sk-")) {
            return "DashScope API Key 应该以 'sk-' 开头，请检查配置";
        }
        
        if (selectedProvider.equals("openai") && !apiKey.startsWith("sk-")) {
            return "OpenAI API Key 应该以 'sk-' 开头，请检查配置";
        }
        
        return null;
    }

    /**
     * 根据名称获取 Provider 配置
     */
    @JsonIgnore
    public ProvidersConfig.ProviderConfig getProviderConfigByName(String providerName) {
        if (providers == null) return null;
        
        return switch (providerName) {
            case "dashscope" -> providers.getDashscope();
            case "openai" -> providers.getOpenai();
            case "openrouter" -> providers.getOpenrouter();
            case "anthropic" -> providers.getAnthropic();
            case "zhipu" -> providers.getZhipu();
            case "gemini" -> providers.getGemini();
            case "ollama" -> providers.getOllama();
            default -> null;
        };
    }

    private boolean isValidWorkspace() {
        return agent != null && agent.getWorkspace() != null && !agent.getWorkspace().isEmpty();
    }

    public static Config defaultConfig() {
        Config config = new Config();
        // 确保所有配置对象不为 null
        if (config.getAgent() == null) {
            config.setAgent(new AgentConfig());
        }
        if (config.getProviders() == null) {
            config.setProviders(new ProvidersConfig());
        }
        if (config.getChannels() == null) {
            config.setChannels(new ChannelsConfig());
        }
        if (config.getTools() == null) {
            config.setTools(new ToolsConfig());
        }
        if (config.getGateway() == null) {
            config.setGateway(new GatewayConfig());
        }
        
        setAgentDefaults(config);
        setGatewayDefaults(config);
        setToolsDefaults(config);
        setProvidersDefaults(config);
        
        return config;
    }

    private static void setAgentDefaults(Config config) {
        config.getAgent().setWorkspace("~/.jobclaw/workspace");
        config.getAgent().setModel("qwen3.5-plus");
        config.getAgent().setProvider("dashscope");
        config.getAgent().setMaxTokens(16384);
        config.getAgent().setTemperature(0.7);
        config.getAgent().setMaxToolIterations(20);
    }

    private static void setGatewayDefaults(Config config) {
        config.getGateway().setHost("0.0.0.0");
        config.getGateway().setPort(18791);
    }

    private static void setProvidersDefaults(Config config) {
        // 确保所有 provider 配置不为 null，并设置默认 API Base
        if (config.getProviders().getDashscope() == null) {
            config.getProviders().setDashscope(
                new ProvidersConfig.ProviderConfig("https://dashscope.aliyuncs.com/compatible-mode/v1")
            );
        }
        if (config.getProviders().getOpenai() == null) {
            config.getProviders().setOpenai(
                new ProvidersConfig.ProviderConfig("https://api.openai.com/v1")
            );
        }
        if (config.getProviders().getOllama() == null) {
            config.getProviders().setOllama(
                new ProvidersConfig.ProviderConfig("http://localhost:11434/v1")
            );
        }
    }

    private static void setToolsDefaults(Config config) {
        // 确保 tools 和 web.search 不为 null
        if (config.getTools() == null) {
            config.setTools(new ToolsConfig());
        }
        if (config.getTools().getWeb() == null) {
            config.getTools().setWeb(new ToolsConfig.WebToolsConfig());
        }
        if (config.getTools().getWeb().getSearch() == null) {
            config.getTools().getWeb().setSearch(new ToolsConfig.WebSearchConfig());
        }
        config.getTools().getWeb().getSearch().setMaxResults(5);
        // 注意：API Key 需要用户在配置文件中手动填写
        // config.getTools().getWeb().getSearch().setApiKey("");
    }
}
