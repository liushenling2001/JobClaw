package io.jobclaw.config;

import io.jobclaw.agent.AgentLoop;
import io.jobclaw.agent.completion.CompletionRegistry;
import io.jobclaw.agent.manifest.ActiveManifestRegistry;
import io.jobclaw.agent.skill.ActiveSkillRegistry;
import io.jobclaw.agent.catalog.AgentCatalogService;
import io.jobclaw.agent.catalog.AgentCatalogStore;
import io.jobclaw.agent.catalog.FileAgentCatalogStore;
import io.jobclaw.agent.experience.ExperienceMemoryStore;
import io.jobclaw.agent.experience.ExperienceMemoryRetriever;
import io.jobclaw.agent.experience.FileExperienceMemoryStore;
import io.jobclaw.agent.learning.FileLearningCandidateStore;
import io.jobclaw.agent.learning.LearningCandidateStore;
import io.jobclaw.board.SharedBoardService;
import io.jobclaw.board.file.FileSharedBoardService;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.channels.ChannelManager;
import io.jobclaw.conversation.ConversationStore;
import io.jobclaw.conversation.file.FileConversationStore;
import io.jobclaw.context.ContextAssembler;
import io.jobclaw.context.ContextAssemblyPolicy;
import io.jobclaw.context.DefaultContextAssemblyPolicy;
import io.jobclaw.context.DefaultContextAssembler;
import io.jobclaw.context.result.FileResultStore;
import io.jobclaw.context.result.ResultStore;
import io.jobclaw.cron.CronService;
import io.jobclaw.cron.CronJobDispatcher;
import io.jobclaw.mcp.MCPService;
import io.jobclaw.providers.LLMProvider;
import io.jobclaw.providers.SpringAiLLMProvider;
import io.jobclaw.retrieval.RetrievalService;
import io.jobclaw.retrieval.SqliteRetrievalService;
import io.jobclaw.runtime.provider.ProviderRuntime;
import io.jobclaw.session.SessionManager;
import io.jobclaw.skills.SkillsLoader;
import io.jobclaw.skills.SkillsService;
import io.jobclaw.stats.TokenUsageService;
import io.jobclaw.summary.SummaryService;
import io.jobclaw.summary.file.FileSummaryService;
import io.jobclaw.tools.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Paths;

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
    public ChannelsConfig channelsConfig(Config config) {
        return config.getChannels();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolsConfig toolsConfig(Config config) {
        return config.getTools() != null ? config.getTools() : new ToolsConfig();
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
    public ConversationStore conversationStore(Config config) {
        return new FileConversationStore(Paths.get(config.getWorkspacePath(), "sessions", "conversation").toString());
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
        return new SessionManager(Paths.get(config.getWorkspacePath(), "sessions").toString(), conversationStore, summaryService);
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
    public AgentCatalogStore agentCatalogStore(Config config) {
        return new FileAgentCatalogStore(
                Paths.get(config.getWorkspacePath(), ".jobclaw", "agents").toString()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentCatalogService agentCatalogService(AgentCatalogStore agentCatalogStore) {
        return new AgentCatalogService(agentCatalogStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public SharedBoardService sharedBoardService(Config config) {
        return new FileSharedBoardService(
                Paths.get(config.getWorkspacePath(), "sessions", "conversation", "boards").toString()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public LearningCandidateStore learningCandidateStore(Config config) {
        return new FileLearningCandidateStore(
                Paths.get(config.getWorkspacePath(), ".jobclaw", "learning").toString()
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
    public ResultStore resultStore(Config config) {
        return new FileResultStore(
                Paths.get(config.getWorkspacePath(), ".jobclaw", "results"),
                config.getAgent().getContextRefPreviewChars()
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
    public CronService cronService(Config config, CronJobDispatcher cronJobDispatcher) {
        CronService cronService = new CronService(config.getWorkspacePath());
        cronService.setOnJob(cronJobDispatcher);
        return cronService;
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
            SharedBoardTool sharedBoardTool,
            AgentCatalogTool agentCatalogTool,
            MemoryTool memoryTool,
            ContextRefTool contextRefTool,
            ManifestTool manifestTool,
            CompletionTool completionTool,
            SpawnTool spawnTool,
            CollaborateTool collaborateTool) {

        // SpawnTool, CollaborateTool 使用 @Lazy 注入 AgentOrchestrator，避免循环依赖

        return MethodToolCallbackProvider.builder()
                .toolObjects(fileTools, runCommandTool, skillsTools, messageTool, cronTool,
                            mcpTool, tokenUsageTool, webSearchTool, webFetchTool,
                            sharedBoardTool,
                            agentCatalogTool, memoryTool, contextRefTool, manifestTool, completionTool, spawnTool, collaborateTool)
                .build()
                .getToolCallbacks();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentLoop agentLoop(Config config, SessionManager sessionManager,
                               ToolCallback[] allToolCallbacks,
                               io.jobclaw.agent.ContextBuilder contextBuilder,
                               ContextAssembler contextAssembler,
                               ContextAssemblyPolicy contextAssemblyPolicy,
                               SummaryService summaryService,
                               ResultStore resultStore,
                               CompletionRegistry completionRegistry,
                               ActiveSkillRegistry activeSkillRegistry,
                               ActiveManifestRegistry activeManifestRegistry) {
        return new AgentLoop(config, sessionManager, allToolCallbacks, contextBuilder, contextAssembler,
                contextAssemblyPolicy, summaryService, resultStore, completionRegistry, activeSkillRegistry, activeManifestRegistry);
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

}
