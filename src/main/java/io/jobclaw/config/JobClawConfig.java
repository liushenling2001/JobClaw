package io.jobclaw.config;

import io.jobclaw.agent.ContextBuilder;
import io.jobclaw.agent.experience.ExperienceMemoryRetriever;
import io.jobclaw.agent.experience.ExperienceMemoryStore;
import io.jobclaw.agent.experience.FileExperienceMemoryStore;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.conversation.ConversationStore;
import io.jobclaw.conversation.file.FileConversationStore;
import io.jobclaw.context.ContextAssembler;
import io.jobclaw.context.ContextAssemblyPolicy;
import io.jobclaw.context.DefaultContextAssemblyPolicy;
import io.jobclaw.context.DefaultContextAssembler;
import io.jobclaw.cron.CronService;
import io.jobclaw.heartbeat.HeartbeatService;
import io.jobclaw.mcp.MCPService;
import io.jobclaw.providers.LLMProvider;
import io.jobclaw.providers.SpringAiLLMProvider;
import io.jobclaw.retrieval.RetrievalService;
import io.jobclaw.retrieval.SqliteRetrievalService;
import io.jobclaw.runtime.provider.ProviderRuntime;
import io.jobclaw.security.SecurityGuard;
import io.jobclaw.session.SessionManager;
import io.jobclaw.skills.SkillsService;
import io.jobclaw.summary.SummaryService;
import io.jobclaw.summary.file.FileSummaryService;
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
    public ToolsConfig toolsConfig(Config config) {
        return config.getTools() != null ? config.getTools() : new ToolsConfig();
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
    public ContextBuilder contextBuilder(Config config,
                                         SessionManager sessionManager,
                                         SkillsService skillsService,
                                         SummaryService summaryService,
                                         MCPService mcpService) {
        return new ContextBuilder(config, sessionManager, skillsService, summaryService, mcpService);
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
                                             RetrievalService retrievalService,
                                             ExperienceMemoryRetriever experienceMemoryRetriever) {
        return new DefaultContextAssembler(
                sessionManager,
                config.getAgent().getRecentMessagesToKeep(),
                retrievalService,
                experienceMemoryRetriever
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ExperienceMemoryStore experienceMemoryStore(Config config) {
        return new FileExperienceMemoryStore(
                Paths.get(config.getWorkspacePath(), ".jobclaw", "experience").toString()
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
    public LLMProvider llmProvider(Config config, ProviderRuntime providerRuntime) {
        return new SpringAiLLMProvider(config, providerRuntime);
    }

    @Bean
    @ConditionalOnMissingBean
    public CronService cronService(Config config) {
        return new CronService(config.getWorkspacePath());
    }

    @Bean
    @ConditionalOnMissingBean
    public HeartbeatService heartbeatService() {
        return new HeartbeatService();
    }
}
