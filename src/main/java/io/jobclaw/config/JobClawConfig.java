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
import io.jobclaw.skills.SkillsService;
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
    public PathResolver pathResolver(Config config) {
        return new PathResolver(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityGuard securityGuard(Config config) {
        return new SecurityGuard(
                config.getAgent().getWorkspace(),
                config.getAgent().isRestrictToWorkspace(),
                config.getAgent().getCommandBlacklist()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(SecurityGuard securityGuard, PathResolver pathResolver) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool(pathResolver));
        registry.register(new WriteFileTool(pathResolver));
        registry.register(new ListDirTool(pathResolver));
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextBuilder contextBuilder(Config config, SessionManager sessionManager, SkillsService skillsService) {
        return new ContextBuilder(config, sessionManager, skillsService);
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
    public CronService cronService() {
        return new CronService();
    }

    @Bean
    @ConditionalOnMissingBean
    public HeartbeatService heartbeatService() {
        return new HeartbeatService();
    }
}
