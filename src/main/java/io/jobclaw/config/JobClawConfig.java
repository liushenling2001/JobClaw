package io.jobclaw.config;

import io.jobclaw.agent.ContextBuilder;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.conversation.ConversationStore;
import io.jobclaw.conversation.file.FileConversationStore;
import io.jobclaw.context.ContextAssembler;
import io.jobclaw.context.ContextAssemblyPolicy;
import io.jobclaw.context.DefaultContextAssemblyPolicy;
import io.jobclaw.context.DefaultContextAssembler;
import io.jobclaw.cron.CronService;
import io.jobclaw.heartbeat.HeartbeatService;
import io.jobclaw.providers.HTTPProvider;
import io.jobclaw.providers.LLMProvider;
import io.jobclaw.retrieval.RetrievalService;
import io.jobclaw.retrieval.SqliteRetrievalService;
import io.jobclaw.security.SecurityGuard;
import io.jobclaw.session.SessionManager;
import io.jobclaw.skills.SkillsService;
import io.jobclaw.summary.SummaryService;
import io.jobclaw.summary.file.FileSummaryService;
import io.jobclaw.tools.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

@Configuration
public class JobClawConfig {

    @Bean
    @ConditionalOnMissingBean
    public Config config() {
        return Config.defaultConfig();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationStore conversationStore(Config config) {
        return new FileConversationStore(Paths.get(config.getWorkspacePath(), "sessions", "conversation").toString());
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
    public ContextBuilder contextBuilder(Config config,
                                         SessionManager sessionManager,
                                         SkillsService skillsService,
                                         SummaryService summaryService) {
        return new ContextBuilder(config, sessionManager, skillsService, summaryService);
    }

    @Bean
    @ConditionalOnMissingBean
    public SummaryService summaryService(Config config) {
        return new FileSummaryService(Paths.get(config.getWorkspacePath(), "sessions", "conversation").toString());
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionManager sessionManager(Config config,
                                         ConversationStore conversationStore,
                                         SummaryService summaryService) {
        return new SessionManager(config.getWorkspacePath() + "/sessions", conversationStore, summaryService);
    }

    @Bean
    @ConditionalOnMissingBean
    public RetrievalService retrievalService(Config config,
                                             ConversationStore conversationStore,
                                             SummaryService summaryService) {
        return new SqliteRetrievalService(
                conversationStore,
                summaryService,
                Paths.get(config.getWorkspacePath(), "sessions", "conversation", "search.db").toString()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextAssembler contextAssembler(Config config,
                                             SessionManager sessionManager,
                                             RetrievalService retrievalService) {
        return new DefaultContextAssembler(
                sessionManager,
                config.getAgent().getRecentMessagesToKeep(),
                retrievalService
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextAssemblyPolicy contextAssemblyPolicy(Config config,
                                                       SessionManager sessionManager,
                                                       SummaryService summaryService) {
        return new DefaultContextAssemblyPolicy(config.getAgent(), sessionManager, summaryService);
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
