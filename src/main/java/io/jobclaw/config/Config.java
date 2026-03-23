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
        if (getApiKey().isEmpty()) {
            return Optional.of("未配置任何 LLM Provider 的 API Key");
        }
        if (!isValidWorkspace()) {
            return Optional.of("工作空间路径未配置");
        }
        return Optional.empty();
    }

    private boolean isValidWorkspace() {
        return agent != null && agent.getWorkspace() != null && !agent.getWorkspace().isEmpty();
    }

    public static Config defaultConfig() {
        Config config = new Config();
        setAgentDefaults(config);
        setGatewayDefaults(config);
        setToolsDefaults(config);
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

    private static void setToolsDefaults(Config config) {
        config.getTools().getWeb().getSearch().setMaxResults(5);
        // 注意：API Key 需要用户在配置文件中手动填写
        // config.getTools().getWeb().getSearch().setApiKey("");
    }
}
