package io.jobclaw.config;

import io.jobclaw.agent.AgentLoop;
import io.jobclaw.agent.ContextBuilder;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.cron.CronService;
import io.jobclaw.heartbeat.HeartbeatService;
import io.jobclaw.providers.HTTPProvider;
import io.jobclaw.providers.LLMProvider;
import io.jobclaw.security.SecurityGuard;
import io.jobclaw.session.SessionManager;
import io.jobclaw.tools.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JobClawConfig {

    @Bean
    @ConditionalOnMissingBean
    public Config config() {
        return Config.defaultConfig();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionManager sessionManager(Config config) {
        return new SessionManager(config.getWorkspacePath() + "/sessions");
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageBus messageBus() {
        return new MessageBus();
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityGuard securityGuard(Config config) {
        return new SecurityGuard(
                config.getWorkspacePath(),
                config.getAgent().isRestrictToWorkspace(),
                config.getAgent().getCommandBlacklist()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(SecurityGuard securityGuard) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new ListDirTool());
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextBuilder contextBuilder(Config config, SessionManager sessionManager, ToolRegistry toolRegistry) {
        return new ContextBuilder(config, sessionManager, toolRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public LLMProvider llmProvider(Config config) {
        HTTPProvider provider = new HTTPProvider();
        provider.setApiKey(config.getApiKey());
        provider.setApiBase(config.getApiBase());
        provider.setModel(config.getAgent().getModel());
        return provider;
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentLoop agentLoop(Config config, SessionManager sessionManager, ContextBuilder contextBuilder,
                                LLMProvider provider, ToolRegistry toolRegistry, MessageBus messageBus) {
        return new AgentLoop(config, sessionManager, contextBuilder, provider, toolRegistry, messageBus);
    }

    @Bean
    @ConditionalOnMissingBean
    public CronService cronService() {
        return new CronService();
    }

    @Bean
    @ConditionalOnMissingBean
    public HeartbeatService heartbeatService() {
        return new HeartbeatService();
    }
}
