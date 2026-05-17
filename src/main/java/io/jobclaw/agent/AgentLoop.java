package io.jobclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jobclaw.agent.evolution.MemoryEvolver;
import io.jobclaw.agent.evolution.MemoryStore;
import io.jobclaw.agent.runtime.AgentRunIds;
import io.jobclaw.agent.completion.ActiveExecutionRegistry;
import io.jobclaw.agent.completion.CompletionGateResult;
import io.jobclaw.agent.completion.CompletionRegistry;
import io.jobclaw.agent.manifest.ActiveManifestRegistry;
import io.jobclaw.agent.skill.ActiveSkillRegistry;
import io.jobclaw.context.ContextAssembler;
import io.jobclaw.context.ContextAssemblyOptions;
import io.jobclaw.context.ContextAssemblyPolicy;
import io.jobclaw.config.Config;
import io.jobclaw.context.result.NoopResultStore;
import io.jobclaw.context.result.ResultStore;
import io.jobclaw.runtime.provider.ProviderRuntime;
import io.jobclaw.runtime.provider.ResolvedProviderConfig;
import io.jobclaw.runtime.tool.DefaultToolExecutionStateTracker;
import io.jobclaw.runtime.tool.ToolExecutionRequest;
import io.jobclaw.runtime.tool.ToolExecutionResult;
import io.jobclaw.runtime.tool.ToolExecutionStateTracker;
import io.jobclaw.runtime.tool.ToolRuntime;
import io.jobclaw.session.Session;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.SummaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * AgentLoop - 基于 Spring AI 重构（使用 OpenAI 兼容模式支持 DashScope Coding Plan）
 *
 * 增强功能：
 * - 集成 ContextBuilder 用于上下文构建
 * - 集成 SessionSummarizer 用于会话摘要
 * - 集成 MemoryStore 用于记忆管理
 * - 支持 LLM 调用用于摘要生成
 */
public class AgentLoop {

    private static final Logger logger = LoggerFactory.getLogger(AgentLoop.class);

    private final Config config;
    private final SessionManager sessionManager;
    private final ChatClient chatClient;
    private final String model;
    private final ToolCallback[] allToolCallbacks;

    // 新增组件
    private final ContextBuilder contextBuilder;
    private final ContextAssembler contextAssembler;
    private final ContextAssemblyPolicy contextAssemblyPolicy;
    private final SessionSummarizer sessionSummarizer;
    private final ProviderRuntime providerRuntime;
    private final ToolRuntime toolRuntime;
    private final ToolExecutionStateTracker toolExecutionStateTracker;
    private final ResolvedProviderConfig defaultProviderConfig;
    private final ActiveExecutionRegistry activeExecutionRegistry;
    private final CompletionRegistry completionRegistry;
    private final ActiveSkillRegistry activeSkillRegistry;
    private final ActiveManifestRegistry activeManifestRegistry;
    private final ResultStore resultStore;

    // 无工具调用的专用 ChatClient（用于摘要生成）
    private final ChatClient simpleChatClient;

    private final ExecutorService toolExecutionExecutor;
    private static final String ACTIVE_SKILL_FRAME_MARKER = "[[JOBCLAW_ACTIVE_SKILL_FRAME]]";
    private static final String ACTIVE_MANIFEST_FRAME_MARKER = "[[JOBCLAW_CURRENT_RUN_MANIFESTS]]";
    private static final int MANAGED_MANIFEST_MAX_CONTINUATIONS = 200;
    private static final String MANAGED_MANIFEST_TAKEOVER_REASON = "managed manifest control returned to framework loop";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int ASSISTANT_DRAFT_CHECKPOINT_CHARS = 1000;

    public AgentLoop(Config config, SessionManager sessionManager,
                     ToolCallback[] allToolCallbacks,
                     ContextBuilder contextBuilder,
                     ContextAssembler contextAssembler,
                     ContextAssemblyPolicy contextAssemblyPolicy,
                     SummaryService summaryService) {
        this(config, sessionManager, allToolCallbacks, contextBuilder, contextAssembler, contextAssemblyPolicy,
                summaryService, new NoopResultStore());
    }

    public AgentLoop(Config config, SessionManager sessionManager,
                     ToolCallback[] allToolCallbacks,
                     ContextBuilder contextBuilder,
                     ContextAssembler contextAssembler,
                     ContextAssemblyPolicy contextAssemblyPolicy,
                     SummaryService summaryService,
                     ResultStore resultStore) {
        this(config, sessionManager, allToolCallbacks, contextBuilder, contextAssembler, contextAssemblyPolicy,
                summaryService, resultStore, new CompletionRegistry(config));
    }

    public AgentLoop(Config config, SessionManager sessionManager,
                     ToolCallback[] allToolCallbacks,
                     ContextBuilder contextBuilder,
                     ContextAssembler contextAssembler,
                     ContextAssemblyPolicy contextAssemblyPolicy,
                     SummaryService summaryService,
                     ResultStore resultStore,
                     CompletionRegistry completionRegistry) {
        this(config, sessionManager, allToolCallbacks, contextBuilder, contextAssembler, contextAssemblyPolicy,
                summaryService, resultStore, completionRegistry, new ActiveSkillRegistry());
    }

    public AgentLoop(Config config, SessionManager sessionManager,
                     ToolCallback[] allToolCallbacks,
                     ContextBuilder contextBuilder,
                     ContextAssembler contextAssembler,
                     ContextAssemblyPolicy contextAssemblyPolicy,
                     SummaryService summaryService,
                     ResultStore resultStore,
                     CompletionRegistry completionRegistry,
                     ActiveSkillRegistry activeSkillRegistry) {
        this(config, sessionManager, allToolCallbacks, contextBuilder, contextAssembler, contextAssemblyPolicy,
                summaryService, resultStore, completionRegistry, activeSkillRegistry, new ActiveManifestRegistry());
    }

    public AgentLoop(Config config, SessionManager sessionManager,
                     ToolCallback[] allToolCallbacks,
                     ContextBuilder contextBuilder,
                     ContextAssembler contextAssembler,
                     ContextAssemblyPolicy contextAssemblyPolicy,
                     SummaryService summaryService,
                     ResultStore resultStore,
                     CompletionRegistry completionRegistry,
                     ActiveSkillRegistry activeSkillRegistry,
                     ActiveManifestRegistry activeManifestRegistry) {
        this(config, sessionManager, allToolCallbacks, contextBuilder, contextAssembler, contextAssemblyPolicy,
                summaryService, null, null, new ProviderRuntime(), new ActiveExecutionRegistry(), resultStore,
                completionRegistry, activeSkillRegistry, activeManifestRegistry);
    }

    /**
     * 构造 AgentLoop，初始化所有组件。
     */
    public AgentLoop(Config config, SessionManager sessionManager,
                     ToolCallback[] allToolCallbacks,
                     ContextBuilder contextBuilder,
                     ContextAssembler contextAssembler,
                     ContextAssemblyPolicy contextAssemblyPolicy,
                     SummaryService summaryService,
                     ChatClient chatClient, String model) {
        this(config, sessionManager, allToolCallbacks, contextBuilder, contextAssembler, contextAssemblyPolicy,
                summaryService, chatClient, model, new ProviderRuntime(), new ActiveExecutionRegistry(), new NoopResultStore(), new CompletionRegistry(config), new ActiveSkillRegistry(), new ActiveManifestRegistry());
    }

    AgentLoop(Config config, SessionManager sessionManager,
                     ToolCallback[] allToolCallbacks,
                     ContextBuilder contextBuilder,
                     ContextAssembler contextAssembler,
                     ContextAssemblyPolicy contextAssemblyPolicy,
                     SummaryService summaryService,
                     ChatClient chatClient, String model,
                     ProviderRuntime providerRuntime) {
        this(config, sessionManager, allToolCallbacks, contextBuilder, contextAssembler, contextAssemblyPolicy,
                summaryService, chatClient, model, providerRuntime, new ActiveExecutionRegistry(), new NoopResultStore(), new CompletionRegistry(config), new ActiveSkillRegistry(), new ActiveManifestRegistry());
    }

    AgentLoop(Config config, SessionManager sessionManager,
                     ToolCallback[] allToolCallbacks,
                     ContextBuilder contextBuilder,
                     ContextAssembler contextAssembler,
                     ContextAssemblyPolicy contextAssemblyPolicy,
                     SummaryService summaryService,
                     ChatClient chatClient, String model,
                     ProviderRuntime providerRuntime,
                     ActiveExecutionRegistry activeExecutionRegistry) {
        this(config, sessionManager, allToolCallbacks, contextBuilder, contextAssembler, contextAssemblyPolicy,
                summaryService, chatClient, model, providerRuntime, activeExecutionRegistry, new NoopResultStore(), new CompletionRegistry(config), new ActiveSkillRegistry(), new ActiveManifestRegistry());
    }

    AgentLoop(Config config, SessionManager sessionManager,
                     ToolCallback[] allToolCallbacks,
                     ContextBuilder contextBuilder,
                     ContextAssembler contextAssembler,
                     ContextAssemblyPolicy contextAssemblyPolicy,
                     SummaryService summaryService,
                     ChatClient chatClient, String model,
                     ProviderRuntime providerRuntime,
                     ActiveExecutionRegistry activeExecutionRegistry,
                     ResultStore resultStore) {
        this(config, sessionManager, allToolCallbacks, contextBuilder, contextAssembler, contextAssemblyPolicy,
                summaryService, chatClient, model, providerRuntime, activeExecutionRegistry, resultStore, new CompletionRegistry(config), new ActiveSkillRegistry(), new ActiveManifestRegistry());
    }

    AgentLoop(Config config, SessionManager sessionManager,
                     ToolCallback[] allToolCallbacks,
                     ContextBuilder contextBuilder,
                     ContextAssembler contextAssembler,
                     ContextAssemblyPolicy contextAssemblyPolicy,
                     SummaryService summaryService,
                     ChatClient chatClient, String model,
                     ProviderRuntime providerRuntime,
                     ActiveExecutionRegistry activeExecutionRegistry,
                     ResultStore resultStore,
                     CompletionRegistry completionRegistry) {
        this(config, sessionManager, allToolCallbacks, contextBuilder, contextAssembler, contextAssemblyPolicy,
                summaryService, chatClient, model, providerRuntime, activeExecutionRegistry, resultStore,
                completionRegistry, new ActiveSkillRegistry(), new ActiveManifestRegistry());
    }

    AgentLoop(Config config, SessionManager sessionManager,
                     ToolCallback[] allToolCallbacks,
                     ContextBuilder contextBuilder,
                     ContextAssembler contextAssembler,
                     ContextAssemblyPolicy contextAssemblyPolicy,
                     SummaryService summaryService,
                     ChatClient chatClient, String model,
                     ProviderRuntime providerRuntime,
                     ActiveExecutionRegistry activeExecutionRegistry,
                     ResultStore resultStore,
                     CompletionRegistry completionRegistry,
                     ActiveSkillRegistry activeSkillRegistry,
                     ActiveManifestRegistry activeManifestRegistry) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.allToolCallbacks = allToolCallbacks;
        this.providerRuntime = providerRuntime;
        this.activeExecutionRegistry = activeExecutionRegistry;
        this.completionRegistry = completionRegistry != null ? completionRegistry : new CompletionRegistry(config);
        this.activeSkillRegistry = activeSkillRegistry != null ? activeSkillRegistry : new ActiveSkillRegistry();
        this.activeManifestRegistry = activeManifestRegistry != null ? activeManifestRegistry : new ActiveManifestRegistry();
        this.resultStore = resultStore != null ? resultStore : new NoopResultStore();

        ResolvedProviderConfig resolvedProvider = providerRuntime.resolve(config, model);
        this.defaultProviderConfig = resolvedProvider;
        this.model = resolvedProvider.model();

        if (resolvedProvider.fallbackUsed()) {
            logger.warn("requested provider not available, falling back to provider '{}'", resolvedProvider.providerName());
        }

        logger.info("Spring AI OpenAI Compatible config - apiKey: {}***, model: {}, apiBase: {} -> using: {}",
                resolvedProvider.apiKey() != null && resolvedProvider.apiKey().length() > 4
                        ? resolvedProvider.apiKey().substring(0, 4)
                        : "null",
                this.model, resolvedProvider.apiBase(), resolvedProvider.springAiBaseUrl());

        // 创建 OpenAI API 客户端（支持自定义 baseUrl，兼容 DashScope Coding Plan）
        OpenAiApi openAiApi = createOpenAiApi(resolvedProvider);

        // 创建 ChatModel（带工具调用）
        OpenAiChatModel.Builder chatModelBuilder = OpenAiChatModel.builder()
                .openAiApi(openAiApi);
        OpenAiChatOptions defaultOptions = deepSeekThinkingDisabledOptions(
                resolvedProvider.providerName(), resolvedProvider.model());
        if (defaultOptions != null) {
            chatModelBuilder.defaultOptions(defaultOptions);
        }
        OpenAiChatModel chatModel = chatModelBuilder.build();

        // 创建 ChatClient（带工具调用）
        this.chatClient = chatClient != null ? chatClient : ChatClient.builder(chatModel).build();

        // 创建简单的 ChatClient（用于摘要生成，不带工具调用）
        OpenAiChatModel.Builder simpleChatModelBuilder = OpenAiChatModel.builder()
                .openAiApi(openAiApi);
        if (defaultOptions != null) {
            simpleChatModelBuilder.defaultOptions(defaultOptions);
        }
        OpenAiChatModel simpleChatModel = simpleChatModelBuilder.build();
        this.simpleChatClient = ChatClient.builder(simpleChatModel).build();

        // 初始化 ContextBuilder（需要 SkillsService）
        io.jobclaw.skills.SkillsService skillsService = null;
        this.contextBuilder = contextBuilder;
        this.contextAssembler = contextAssembler;
        this.contextAssemblyPolicy = contextAssemblyPolicy;

        // 设置上下文窗口
        this.contextBuilder.setContextWindow(config.getAgent().getContextWindow());

        // 获取记忆存储
        MemoryStore memoryStore = contextBuilder.getMemoryStore();

        // 初始化记忆进化引擎
        MemoryEvolver memoryEvolver = new MemoryEvolver(memoryStore, this, this.model);

        // 初始化会话摘要器（传入 AgentConfig）
        this.sessionSummarizer = new SessionSummarizer(
                sessionManager,
                this,
                config.getAgent(),
                memoryStore,
                memoryEvolver,
                summaryService
        );

        // 初始化 THINK_STREAM 缓冲区
        this.toolExecutionExecutor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "jobclaw-tool-call");
            thread.setDaemon(true);
            return thread;
        });
        this.toolExecutionStateTracker = new DefaultToolExecutionStateTracker();
        this.toolRuntime = new ToolRuntime(config, sessionManager, toolExecutionExecutor, toolExecutionStateTracker,
                activeExecutionRegistry, this.resultStore);

        logger.info("AgentLoop initialized with {} tools from Spring context", this.allToolCallbacks.length);
        for (ToolCallback callback : this.allToolCallbacks) {
            logger.debug("  - Tool: {}", callback.getToolDefinition().name());
        }
    }

    /**
     * 获取 ContextBuilder 实例。
     *
     * @return ContextBuilder 实例
     */
    public ContextBuilder getContextBuilder() {
        return contextBuilder;
    }

    /**
     * 获取 SessionSummarizer 实例。
     *
     * @return SessionSummarizer 实例
     */
    public SessionSummarizer getSessionSummarizer() {
        return sessionSummarizer;
    }

    /**
     * 获取记忆存储实例。
     *
     * @return MemoryStore 实例
     */
    public MemoryStore getMemoryStore() {
        return contextBuilder.getMemoryStore();
    }

    /**
     * 处理消息（带工具调用）
     */
    public String process(String sessionKey, String userContent) {
        return processWithDefinition(sessionKey, userContent, null);
    }

    /**
     * 处理消息（带工具调用和角色指定）
     *
     * @param sessionKey  会话密钥
     * @param userContent 用户输入内容
     * @param role        Agent 角色（可选，null 表示使用默认角色）
     * @return Agent 响应
     */
    public String process(String sessionKey, String userContent, AgentRole role) {
        return processWithDefinition(sessionKey, userContent, role != null ? AgentDefinition.fromRole(role) : null);
    }

    /**
     * 处理消息（带工具调用和 Agent 定义）
     *
     * @param sessionKey  会话密钥
     * @param userContent 用户输入内容
     * @param definition  Agent 定义（可选，null 表示使用默认配置）
     * @return Agent 响应
     */
    public String processWithDefinition(String sessionKey, String userContent, AgentDefinition definition) {
        return processWithDefinition(sessionKey, userContent, definition, null);
    }

    /**
     * 处理消息（带工具调用和 Agent 定义，支持执行过程回调）
     *
     * @param sessionKey    会话密钥
     * @param userContent   用户输入内容
     * @param definition    Agent 定义（可选，null 表示使用默认配置）
     * @param eventCallback 执行事件回调（可选，null 表示不使用回调）
     * @return Agent 响应
     */
    public String processWithDefinition(String sessionKey, String userContent, AgentDefinition definition,
                                        Consumer<ExecutionEvent> eventCallback) {
        // 设置执行上下文（供 SpawnTool/CollaborateTool 获取 sessionKey）
        AgentExecutionContext.ExecutionScope previousScope = AgentExecutionContext.getCurrentScope();
        AgentExecutionContext.ExecutionScope scope = createExecutionScope(
                sessionKey,
                definition,
                eventCallback,
                previousScope
        );
        AgentExecutionContext.setCurrentContext(scope);
        boolean userMessagePersisted = false;
        boolean assistantMessagePersisted = false;
        StringBuilder fullResponse = new StringBuilder();
        long runStartAt = System.currentTimeMillis();
        int managedManifestContinuations = 0;
        boolean managedManifestHandoffIssued = false;
        int managedCreateRepairAttempts = 0;

        try {
            Session session = sessionManager.getOrCreate(sessionKey);

            logger.info("agent run start session={} run={} parentRun={} agent={} definition={} model={} userChars={}",
                    sessionKey,
                    scope.runId(),
                    scope.parentRunId(),
                    scope.agentName() != null ? scope.agentName() : "default",
                    definition != null ? definition.getCode() : "default",
                    config.getAgent().getModel(),
                    userContent != null ? userContent.length() : 0);
            long startTime = System.currentTimeMillis();

            // 发布思考开始事件
            if (eventCallback != null) {
                eventCallback.accept(new ExecutionEvent(sessionKey, ExecutionEvent.EventType.THINK_START,
                        "Agent 开始思考..."));
            }

            // 使用 ContextBuilder 构建系统提示（支持 Agent 定义）
            String systemPrompt = definition != null ?
                    buildSystemPromptWithDefinition(sessionKey, userContent, definition) : buildSystemPrompt(sessionKey, userContent);

            // 创建工具回调（支持工具过滤）
            ToolCallback[] rawTools = filterToolsByDefinition(definition, userContent);
            ToolCallback[] tools = wrapToolCallbacks(rawTools, sessionKey, eventCallback);

            ExecutionClientBundle executionClientBundle = createExecutionClientBundle(definition);
            OpenAiChatOptions options = buildExecutionOptions(definition, executionClientBundle.model(),
                    executionClientBundle.providerName());

            // 使用结构化上下文装配器，保留消息边界，不再拍平成单段文本
            ContextAssemblyOptions assemblyOptions = contextAssemblyPolicy.buildOptions(sessionKey, userContent);
            List<io.jobclaw.providers.Message> historyMessages =
                    contextAssembler.assemble(sessionKey, userContent, assemblyOptions);
            List<Message> promptMessages = buildPromptMessages(systemPrompt, historyMessages, userContent);

            // Persist the user turn before model execution so interrupted or failed
            // runs still appear in conversation history.
            sessionManager.addMessage(sessionKey, "user", userContent);
            userMessagePersisted = true;

            String finalResponse = "";
            while (true) {
                refreshActiveSkillFrame(promptMessages, scope.sessionKey(), scope.runId());
                refreshActiveManifestFrame(promptMessages, scope.sessionKey(), scope.runId());
                String attemptResponse = runModelAttempt(
                        executionClientBundle,
                        promptMessages,
                        tools,
                        options,
                        sessionKey,
                        eventCallback,
                        fullResponse,
                        true
                );

                String createRepairPrompt = buildManagedCreateRepairPrompt(
                        scope.sessionKey(),
                        scope.runId(),
                        attemptResponse,
                        managedCreateRepairAttempts
                );
                if (!createRepairPrompt.isBlank()) {
                    managedCreateRepairAttempts++;
                    logger.info("managed manifest create repair session={} run={} attempt={} promptChars={}",
                            sessionKey,
                            scope.runId(),
                            managedCreateRepairAttempts,
                            createRepairPrompt.length());
                    promptMessages.add(new AssistantMessage(attemptResponse));
                    promptMessages.add(new UserMessage(createRepairPrompt));
                    continue;
                }

                String managedContinuationPrompt = buildManagedManifestContinuationPrompt(scope.sessionKey(), scope.runId());
                if (!managedContinuationPrompt.isBlank()) {
                    managedManifestContinuations++;
                    if (managedManifestContinuations > MANAGED_MANIFEST_MAX_CONTINUATIONS) {
                        String errorResponse = "Error: managed manifest did not finish after "
                                + MANAGED_MANIFEST_MAX_CONTINUATIONS
                                + " continuation attempt(s). Check the manifest status and continue the task.";
                        logger.warn("managed manifest continuation limit reached session={} run={} responseChars={}",
                                sessionKey,
                                scope.runId(),
                                attemptResponse != null ? attemptResponse.length() : 0);
                        if (eventCallback != null) {
                            eventCallback.accept(new ExecutionEvent(sessionKey, ExecutionEvent.EventType.ERROR, errorResponse));
                        }
                        sessionManager.finalizeAssistantMessage(sessionKey, partialOrErrorResponse(fullResponse, errorResponse));
                        assistantMessagePersisted = true;
                        return errorResponse;
                    }
                    logger.info("managed manifest continuation session={} run={} attempt={} promptChars={}",
                            sessionKey,
                            scope.runId(),
                            managedManifestContinuations,
                            managedContinuationPrompt.length());
                    if (activeSkillRegistry.hasManagedRunnerRuntime(scope.sessionKey(), scope.runId())) {
                        ManagedRunnerResult runnerResult = runManagedSkillRunner(
                                executionClientBundle,
                                tools,
                                options,
                                systemPrompt,
                                userContent,
                                sessionKey,
                                scope.runId(),
                                eventCallback,
                                fullResponse
                        );
                        if (runnerResult.error()) {
                            if (eventCallback != null) {
                                eventCallback.accept(new ExecutionEvent(sessionKey, ExecutionEvent.EventType.ERROR, runnerResult.message()));
                            }
                            sessionManager.finalizeAssistantMessage(sessionKey, partialOrErrorResponse(fullResponse, runnerResult.message()));
                            assistantMessagePersisted = true;
                            return runnerResult.message();
                        }
                        if (!runnerResult.message().isBlank()) {
                            managedManifestHandoffIssued = true;
                            promptMessages.add(new AssistantMessage(attemptResponse));
                            promptMessages.add(new UserMessage(runnerResult.message()));
                            continue;
                        }
                    }
                    {
                        promptMessages.add(new AssistantMessage(attemptResponse));
                        promptMessages.add(new UserMessage(managedContinuationPrompt));
                    }
                    continue;
                }

                if (!managedManifestHandoffIssued) {
                    String managedHandoffPrompt = buildManagedManifestHandoffPrompt(scope.sessionKey(), scope.runId());
                    if (!managedHandoffPrompt.isBlank()) {
                        managedManifestHandoffIssued = true;
                        logger.info("managed manifest handoff session={} run={} promptChars={}",
                                sessionKey,
                                scope.runId(),
                                managedHandoffPrompt.length());
                        promptMessages.add(new AssistantMessage(attemptResponse));
                        promptMessages.add(new UserMessage(managedHandoffPrompt));
                        continue;
                    }
                }

                CompletionGateResult gateResult = completionRegistry.evaluateForFinal(sessionKey, scope.runId(), attemptResponse);
                if (gateResult.passed()) {
                    finalResponse = attemptResponse;
                    break;
                }

                if (!gateResult.canRetry()) {
                    String errorResponse = "Error: completion checks failed after "
                            + gateResult.failedAttempts() + " attempt(s).\n\n"
                            + gateResult.toModelMessage();
                    logger.warn("agent run completion-check failed session={} run={} attempts={} responseChars={}",
                            sessionKey,
                            scope.runId(),
                            gateResult.failedAttempts(),
                            attemptResponse != null ? attemptResponse.length() : 0);
                    if (eventCallback != null) {
                        eventCallback.accept(new ExecutionEvent(sessionKey, ExecutionEvent.EventType.ERROR, errorResponse));
                    }
                    sessionManager.finalizeAssistantMessage(sessionKey, fullResponse + "\n\n" + errorResponse);
                    assistantMessagePersisted = true;
                    return errorResponse;
                }

                String gateMessage = buildCompletionRecoveryPrompt(gateResult, attemptResponse);
                logger.info("agent run completion-check retry session={} run={} attempts={} recoveryPromptChars={} candidateChars={}",
                        sessionKey,
                        scope.runId(),
                        gateResult.failedAttempts(),
                        gateMessage.length(),
                        attemptResponse != null ? attemptResponse.length() : 0);
                promptMessages.add(new AssistantMessage(attemptResponse));
                promptMessages.add(new UserMessage(gateMessage));
            }

            long elapsed = System.currentTimeMillis() - startTime;
            logger.info("agent run final session={} run={} elapsedMs={} responseChars={} accumulatedChars={}",
                    sessionKey,
                    scope.runId(),
                    elapsed,
                    finalResponse != null ? finalResponse.length() : 0,
                    fullResponse.length());

            String response = finalResponse;

            // 发布思考结束事件
            if (eventCallback != null) {
                eventCallback.accept(new ExecutionEvent(sessionKey, ExecutionEvent.EventType.THINK_END,
                        "思考完成，耗时：" + elapsed + "ms"));
                eventCallback.accept(new ExecutionEvent(sessionKey, ExecutionEvent.EventType.FINAL_RESPONSE,
                        response));
            }

            // 保存会话历史
            sessionManager.finalizeAssistantMessage(sessionKey, response);
            assistantMessagePersisted = true;

            // 触发会话摘要检查
            sessionSummarizer.maybeSummarize(sessionKey);

            logger.debug("Processed message for session {} run {} (agent: {})", sessionKey, scope.runId(),
                    definition != null ? definition.getDisplayName() : "default");

            return response;

        } catch (Exception e) {
            logger.error("agent run failed session={} run={} elapsedMs={} accumulatedChars={}",
                    sessionKey,
                    scope.runId(),
                    System.currentTimeMillis() - runStartAt,
                    fullResponse.length(),
                    e);
            if (!userMessagePersisted) {
                sessionManager.addMessage(sessionKey, "user", userContent);
                userMessagePersisted = true;
            }
            if (containsInterruptedException(e)) {
                Thread.currentThread().interrupt();
                if (!assistantMessagePersisted) {
                    sessionManager.finalizeAssistantMessage(sessionKey,
                            partialOrErrorResponse(fullResponse, "Error: execution interrupted"));
                    assistantMessagePersisted = true;
                }
                if (eventCallback != null) {
                    eventCallback.accept(new ExecutionEvent(sessionKey, ExecutionEvent.EventType.ERROR,
                            "Error: execution interrupted"));
                }
                return "Error: execution interrupted";
            }
            String errorResponse = "Error: " + e.getMessage() + " (check network/API key)";
            if (!assistantMessagePersisted) {
                sessionManager.finalizeAssistantMessage(sessionKey,
                        partialOrErrorResponse(fullResponse, errorResponse));
                assistantMessagePersisted = true;
            }
            if (eventCallback != null) {
                eventCallback.accept(new ExecutionEvent(sessionKey, ExecutionEvent.EventType.ERROR,
                        "Error: " + e.getMessage()));
            }
            return errorResponse;
        } finally {
            toolRuntime.clearRunState(scope.sessionKey(), scope.runId());
            activeSkillRegistry.clear(scope.sessionKey(), scope.runId());
            activeManifestRegistry.clear(scope.sessionKey(), scope.runId());
            logger.info("agent run cleanup session={} run={} elapsedMs={} userPersisted={} assistantPersisted={}",
                    scope.sessionKey(),
                    scope.runId(),
                    System.currentTimeMillis() - runStartAt,
                    userMessagePersisted,
                    assistantMessagePersisted);
            // 清理执行上下文
            if (previousScope != null) {
                AgentExecutionContext.setCurrentContext(previousScope);
            } else {
                AgentExecutionContext.clear();
            }
        }
    }

    private String buildManagedManifestContinuationPrompt(String sessionKey, String runId) {
        return activeManifestRegistry.findManagedBlockingState(sessionKey, runId)
                .map(state -> buildManagedManifestContinuationPrompt(sessionKey, runId, state))
                .orElse("");
    }

    private String buildManagedCreateRepairPrompt(String sessionKey,
                                                  String runId,
                                                  String attemptResponse,
                                                  int repairAttempts) {
        if (repairAttempts >= 2 || !activeSkillRegistry.hasManagedRunnerRuntime(sessionKey, runId)) {
            return "";
        }
        if (activeManifestRegistry.findManagedBlockingState(sessionKey, runId).isPresent()
                || activeManifestRegistry.findManagedHandoffState(sessionKey, runId).isPresent()
                || activeManifestRegistry.findManagedClosedState(sessionKey, runId).isPresent()) {
            return "";
        }
        if (!looksLikeManagedCreateNeedsRepair(attemptResponse)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("JOBCLAW_MANAGED_MANIFEST_CREATE_REPAIR\n");
        sb.append("The active skill declares a managed runner, but the managed manifest has not been created successfully.\n");
        sb.append("Do not answer the user yet. Repair only the setup step and retry the necessary tool call(s).\n\n");
        sb.append("Required behavior:\n");
        sb.append("- If a current-run list_dir result already contains the real input files, use that result. Do not ask the user to confirm.\n");
        sb.append("- If no current-run file list is available, call list_dir once for the current input directory from the user request.\n");
        sb.append("- Call manifest(action='create', ...) with real item objects from the current-run file list.\n");
        sb.append("- manifest.create must include taskKey, items, schema, artifactPath, and executionMode='managed'.\n");
        sb.append("- items must be a JSON array of real objects such as {\"id\":\"relative-file-name.md\",\"title\":\"relative-file-name.md\"}; never use examples or placeholders.\n");
        sb.append("- schema must describe the user-requested columns, for example {\"columns\":[\"字段1\",\"字段2\"]}.\n");
        sb.append("- artifactPath is the intermediate aggregate path required by the skill for this item loop.\n");
        sb.append("- Do not invent the next-stage/final artifact contract during create unless the active skill explicitly asks for it in the current setup card.\n");
        sb.append("- After manifest.create succeeds, stop choosing the next item yourself; the framework will enter the managed runner.\n\n");
        if (attemptResponse != null && !attemptResponse.isBlank()) {
            sb.append("Previous failed setup response:\n```text\n");
            sb.append(truncateForCompletionRecovery(attemptResponse));
            sb.append("\n```\n");
        }
        return sb.toString();
    }

    private boolean looksLikeManagedCreateNeedsRepair(String attemptResponse) {
        if (attemptResponse == null || attemptResponse.isBlank()) {
            return false;
        }
        String lower = attemptResponse.toLowerCase();
        return lower.contains("manifest.create")
                || lower.contains("manifest")
                || lower.contains("schema is required")
                || lower.contains("items are required")
                || lower.contains("managed manifest contract is incomplete")
                || lower.contains("请确认")
                || lower.contains("确认授权");
    }

    private String buildManagedManifestContinuationPrompt(String sessionKey,
                                                          String runId,
                                                          ActiveManifestRegistry.ActiveManifestState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("FRAMEWORK-MANAGED MANIFEST LOOP\n");
        sb.append("A manifest created with executionMode=managed is active. This is a loop-control frame, not a new user task.\n");
        sb.append("Continue the current manifest unit described below. Apply the active skill Runtime Frame or current task instructions for the actual work.\n\n");
        sb.append("manifestId: ").append(state.manifestId()).append("\n");
        if (!state.taskKey().isBlank()) {
            sb.append("taskKey: ").append(state.taskKey()).append("\n");
        }
        if (!state.schema().isBlank()) {
            sb.append("schema: ").append(state.schema()).append("\n");
        }
        if (!state.artifactPath().isBlank()) {
            sb.append("artifactPath: ").append(state.artifactPath()).append("\n");
        }
        if (!state.finalArtifactPath().isBlank()) {
            sb.append("finalArtifactPath: ").append(state.finalArtifactPath()).append("\n");
            if (!state.finalArtifactType().isBlank()) {
                sb.append("finalArtifactType: ").append(state.finalArtifactType()).append("\n");
            }
            sb.append("finalArtifactReady: ").append(isFinalArtifactReadyNow(state)).append("\n");
        }
        sb.append("total: ").append(state.total()).append("\n");
        sb.append("pending: ").append(state.pending()).append("\n");
        sb.append("running: ").append(state.running()).append("\n");
        sb.append("done: ").append(state.done()).append("\n");
        sb.append("failed: ").append(state.failed()).append("\n\n");
        String skillCard = activeSkillRegistry.renderManagedRuntime(sessionKey, runId, "item", state);
        if (!skillCard.isBlank()) {
            sb.append("Skill managed runtime card:\n");
            sb.append(skillCard).append("\n\n");
            sb.append("Framework boundary:\n");
            sb.append("- Execute only the current managed card. Return control through the manifest action named by the skill card.\n");
            return sb.toString();
        }
        sb.append("Current loop unit:\n");
        if (state.running() > 0) {
            appendManagedItem(sb, "running", state.runningItem());
            sb.append("- Continue the listed running item. When the item is complete, call manifest(action='done' or 'fail', manifestId='")
                    .append(state.manifestId()).append("', itemId='<completed item id>').\n");
        } else if (state.pending() > 0) {
            appendManagedItem(sb, "pending", state.nextPendingItem());
            if (state.nextPendingItem() != null) {
                sb.append("- Start and process only the listed pending item, then call manifest(action='done' or 'fail', manifestId='")
                        .append(state.manifestId())
                        .append("', itemId='<completed item id>').\n");
            } else {
                sb.append("- Ask manifest status for one pending item, then mark it done or failed after the active skill or task instructions are satisfied.\n");
            }
        } else if (!state.finalArtifactPath().isBlank() && !isFinalArtifactReadyNow(state)) {
            sb.append("- All manifest items are closed. Required final artifact is not ready: ")
                    .append(state.finalArtifactPath())
                    .append("\n");
        } else {
            sb.append("- The manifest loop is closed. Wait for the handoff frame before final response.\n");
        }
        sb.append("Loop boundaries:\n");
        sb.append("- Keep using this manifestId for this managed run.\n");
        sb.append("- Keep the turn scoped to the manifest state above.\n");
        sb.append("- Do not give a final answer until pending=0, running=0, and any required final artifact is ready.");
        return sb.toString();
    }

    private String buildManagedManifestHandoffPrompt(String sessionKey, String runId) {
        return activeManifestRegistry.findManagedHandoffState(sessionKey, runId)
                .map(state -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("FRAMEWORK-MANAGED MANIFEST HANDOFF\n");
                    sb.append("The managed loop is complete. This frame provides current state only; the next-stage procedure must come from the active skill Runtime Frame or current task instructions.\n\n");
                    sb.append("manifestId: ").append(state.manifestId()).append("\n");
                    if (!state.taskKey().isBlank()) {
                        sb.append("taskKey: ").append(state.taskKey()).append("\n");
                    }
                    if (!state.schema().isBlank()) {
                        sb.append("schema: ").append(state.schema()).append("\n");
                    }
                    if (!state.artifactPath().isBlank()) {
                        sb.append("intermediateArtifactPath: ").append(state.artifactPath()).append("\n");
                    }
                    sb.append("itemArtifactIndex: stored in manifest item artifactPath fields\n");
                    sb.append("itemArtifactAccess: call manifest(action='status', manifestId='")
                            .append(state.manifestId())
                            .append("', includeItems='all', limit='")
                            .append(Math.max(1, state.total()))
                            .append("') only when item-level result locations are needed\n");
                    if (!state.finalArtifactPath().isBlank()) {
                        sb.append("finalArtifactPath: ").append(state.finalArtifactPath()).append("\n");
                        if (!state.finalArtifactType().isBlank()) {
                            sb.append("finalArtifactType: ").append(state.finalArtifactType()).append("\n");
                        }
                        sb.append("finalArtifactReady: ").append(isFinalArtifactReadyNow(state)).append("\n");
                    }
                    sb.append("total: ").append(state.total()).append("\n");
                    sb.append("done: ").append(state.done()).append("\n");
                    sb.append("failed: ").append(state.failed()).append("\n");
                    sb.append("pending: ").append(state.pending()).append("\n");
                    sb.append("running: ").append(state.running()).append("\n\n");
                    String skillCard = activeSkillRegistry.renderManagedRuntime(sessionKey, runId, "finalize", state);
                    if (!skillCard.isBlank()) {
                        sb.append("Skill managed runtime card:\n");
                        sb.append(skillCard).append("\n\n");
                        sb.append("Framework boundary:\n");
                        sb.append("- This is the post-manifest handoff. Do not restart the managed item loop unless the user explicitly asks.\n");
                        return sb.toString();
                    }
                    sb.append("Handoff boundaries:\n");
                    sb.append("- Keep using the manifest state and artifact paths above for this run.\n");
                    sb.append("- Do not create a replacement manifest for this closed managed run unless the user explicitly asks to start over.\n");
                    sb.append("- Follow [[JOBCLAW_ACTIVE_SKILL_FRAME]] or current task instructions for the next stage.\n");
                    sb.append("- If no active skill Runtime Frame or task instruction applies, report the closed manifest state and ask for the next instruction.\n");
                    return sb.toString();
                })
                .orElse("");
    }

    private String buildManagedManifestClosedHandoffPrompt(String sessionKey, String runId) {
        return activeManifestRegistry.findManagedClosedState(sessionKey, runId)
                .map(state -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("FRAMEWORK-MANAGED MANIFEST HANDOFF\n");
                    sb.append("The managed item loop is complete. This frame provides current state only; the next-stage procedure must come from the active skill Runtime Frame or current task instructions.\n\n");
                    sb.append("manifestId: ").append(state.manifestId()).append("\n");
                    if (!state.taskKey().isBlank()) {
                        sb.append("taskKey: ").append(state.taskKey()).append("\n");
                    }
                    if (!state.schema().isBlank()) {
                        sb.append("schema: ").append(state.schema()).append("\n");
                    }
                    if (!state.artifactPath().isBlank()) {
                        sb.append("intermediateArtifactPath: ").append(state.artifactPath()).append("\n");
                    }
                    sb.append("itemArtifactIndex: stored in manifest item artifactPath fields\n");
                    sb.append("itemArtifactAccess: call manifest(action='status', manifestId='")
                            .append(state.manifestId())
                            .append("', includeItems='all', limit='")
                            .append(Math.max(1, state.total()))
                            .append("') only when item-level result locations are needed\n");
                    if (!state.finalArtifactPath().isBlank()) {
                        sb.append("finalArtifactPath: ").append(state.finalArtifactPath()).append("\n");
                        if (!state.finalArtifactType().isBlank()) {
                            sb.append("finalArtifactType: ").append(state.finalArtifactType()).append("\n");
                        }
                        sb.append("finalArtifactReady: ").append(isFinalArtifactReadyNow(state)).append("\n");
                    }
                    sb.append("total: ").append(state.total()).append("\n");
                    sb.append("done: ").append(state.done()).append("\n");
                    sb.append("failed: ").append(state.failed()).append("\n");
                    sb.append("pending: ").append(state.pending()).append("\n");
                    sb.append("running: ").append(state.running()).append("\n\n");
                    String skillCard = activeSkillRegistry.renderManagedRuntime(sessionKey, runId, "finalize", state);
                    if (!skillCard.isBlank()) {
                        sb.append("Skill managed runtime card:\n");
                        sb.append(skillCard).append("\n\n");
                        sb.append("Framework boundary:\n");
                        sb.append("- Main agent flow waited for the managed runner. Continue only with the finalize card; do not restart item processing unless the user explicitly asks.\n");
                        return sb.toString();
                    }
                    sb.append("Handoff boundaries:\n");
                    sb.append("- The managed item loop is closed. Continue with the next stage; do not recreate the manifest.\n");
                    return sb.toString();
                })
                .orElse("");
    }

    private boolean isFinalArtifactReadyNow(ActiveManifestRegistry.ActiveManifestState state) {
        if (state == null || state.finalArtifactPath().isBlank()) {
            return false;
        }
        if (state.finalArtifactReady()) {
            return true;
        }
        try {
            java.nio.file.Path path = java.nio.file.Path.of(state.finalArtifactPath().trim());
            return java.nio.file.Files.isRegularFile(path) && java.nio.file.Files.size(path) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void appendManagedItem(StringBuilder sb, String label, ActiveManifestRegistry.ActiveManifestItem item) {
        if (item == null) {
            sb.append("- ").append(label).append(" item: <not available in current manifest frame>\n");
            return;
        }
        sb.append("- ").append(label).append(" item id: ").append(item.id()).append("\n");
        sb.append("- ").append(label).append(" item title/path: ").append(item.title()).append("\n");
        if (!item.artifactPath().isBlank()) {
            sb.append("- ").append(label).append(" item artifact: ").append(item.artifactPath()).append("\n");
        }
        if (!item.resultRefId().isBlank()) {
            sb.append("- ").append(label).append(" item refId: ").append(item.resultRefId()).append("\n");
        }
        if (!item.error().isBlank()) {
            sb.append("- ").append(label).append(" item error: ").append(item.error()).append("\n");
        }
    }

    private void refreshActiveSkillFrame(List<Message> promptMessages, String sessionKey, String runId) {
        if (promptMessages == null) {
            return;
        }
        promptMessages.removeIf(message ->
                message instanceof SystemMessage
                        && message.getText() != null
                        && message.getText().contains(ACTIVE_SKILL_FRAME_MARKER));

        String frame = activeSkillRegistry.formatForPrompt(sessionKey, runId);
        if (frame == null || frame.isBlank()) {
            return;
        }

        int insertAt = 0;
        if (!promptMessages.isEmpty() && promptMessages.get(0) instanceof SystemMessage) {
            insertAt = 1;
        }
        promptMessages.add(insertAt, new SystemMessage(frame));
    }

    private void refreshActiveManifestFrame(List<Message> promptMessages, String sessionKey, String runId) {
        if (promptMessages == null) {
            return;
        }
        promptMessages.removeIf(message ->
                message instanceof SystemMessage
                        && message.getText() != null
                        && message.getText().contains(ACTIVE_MANIFEST_FRAME_MARKER));

        String frame = activeManifestRegistry.formatForPrompt(sessionKey, runId);
        if (frame == null || frame.isBlank()) {
            return;
        }

        int insertAt = 0;
        if (!promptMessages.isEmpty() && promptMessages.get(0) instanceof SystemMessage) {
            insertAt = 1;
        }
        promptMessages.add(insertAt, new SystemMessage(frame));
    }

    private String buildCompletionRecoveryPrompt(CompletionGateResult gateResult, String attemptResponse) {
        StringBuilder sb = new StringBuilder(gateResult.toModelMessage());
        if (attemptResponse != null && !attemptResponse.isBlank()) {
            sb.append("\n\nYour previous final response candidate is below. Use it as context; do not repeat it as final answer until completion checks pass.\n");
            sb.append("```text\n");
            sb.append(truncateForCompletionRecovery(attemptResponse));
            sb.append("\n```");
        }
        return sb.toString();
    }

    private String truncateForCompletionRecovery(String text) {
        int maxChars = 6000;
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars)
                + "\n...[truncated "
                + (text.length() - maxChars)
                + " chars; provide the concrete artifact path or continue the task]";
    }

    private String partialOrErrorResponse(StringBuilder partialResponse, String errorResponse) {
        if (partialResponse != null && !partialResponse.isEmpty()) {
            return partialResponse.toString();
        }
        return errorResponse;
    }

    private ManagedRunnerResult runManagedSkillRunner(ExecutionClientBundle executionClientBundle,
                                                      ToolCallback[] tools,
                                                      OpenAiChatOptions options,
                                                      String systemPrompt,
                                                      String originalUserContent,
                                                      String sessionKey,
                                                      String runId,
                                                      Consumer<ExecutionEvent> eventCallback,
                                                      StringBuilder fullResponse) {
        logger.info("managed skill runner start session={} run={}", sessionKey, runId);
        int attempts = 0;
        int parallelism = activeSkillRegistry.managedRunnerParallelism(sessionKey, runId);
        while (true) {
            var blockingState = activeManifestRegistry.findManagedBlockingState(sessionKey, runId);
            if (blockingState.isEmpty() || (blockingState.get().pending() == 0 && blockingState.get().running() == 0)) {
                break;
            }
            List<ActiveManifestRegistry.ActiveManifestState> runnerStates = selectManagedRunnerStates(blockingState.get(), parallelism);
            if (runnerStates.isEmpty()) {
                break;
            }
            attempts++;
            if (attempts > MANAGED_MANIFEST_MAX_CONTINUATIONS) {
                String error = "Error: managed runner did not finish after "
                        + MANAGED_MANIFEST_MAX_CONTINUATIONS
                        + " item attempt(s). Check the manifest status and continue the task.";
                logger.warn("managed skill runner limit reached session={} run={}", sessionKey, runId);
                return new ManagedRunnerResult(error, true);
            }
            logger.info("managed skill runner batch session={} run={} attempt={} workers={} configuredParallelism={}",
                    sessionKey, runId, attempts, runnerStates.size(), parallelism);
            List<CompletableFuture<String>> workers = new ArrayList<>();
            ToolCallback[] runnerTools = filterManagedRunnerTools(tools, sessionKey, runId);
            for (ActiveManifestRegistry.ActiveManifestState runnerState : runnerStates) {
                ActiveManifestRegistry.ActiveManifestItem currentItem = managedRunnerItem(runnerState);
                if (currentItem == null || currentItem.id().isBlank()) {
                    continue;
                }
                ensureManagedItemStarted(sessionKey, runId, currentItem, eventCallback);
                ActiveManifestRegistry.ActiveManifestState refreshedState = activeManifestRegistry
                        .findManagedBlockingState(sessionKey, runId)
                        .orElse(runnerState);
                ActiveManifestRegistry.ActiveManifestItem refreshedItem = managedRunnerItem(refreshedState);
                if (refreshedItem == null || !currentItem.id().equals(refreshedItem.id())) {
                    refreshedState = runnerState;
                }
                String runnerPrompt = buildManagedManifestContinuationPrompt(sessionKey, runId, refreshedState);
                if (runnerPrompt.isBlank()) {
                    continue;
                }
                String itemId = currentItem.id();
                String itemArtifactPath = activeSkillRegistry.renderManagedItemResultPath(sessionKey, runId, refreshedState);
                if (itemArtifactPath.isBlank()) {
                    itemArtifactPath = extractManagedItemArtifactPath(runnerPrompt);
                }
                String resolvedItemArtifactPath = itemArtifactPath;
                if (reuseExistingManagedItemArtifact(sessionKey, runId, eventCallback, itemId, resolvedItemArtifactPath)) {
                    continue;
                }
                workers.add(CompletableFuture.supplyAsync(() -> {
                    StringBuilder workerResponse = new StringBuilder();
                    try {
                        List<Message> runnerMessages = buildManagedRunnerMessages(systemPrompt, originalUserContent, runnerPrompt);
                        runModelAttempt(
                                executionClientBundle,
                                runnerMessages,
                                runnerTools,
                                options,
                                sessionKey,
                                eventCallback,
                                workerResponse,
                                false
                        );
                        closeManagedItemFromModelResult(sessionKey, runId, itemId, resolvedItemArtifactPath,
                                workerResponse.toString(), eventCallback);
                    } catch (Exception e) {
                        logger.warn("managed runner item failed session={} run={} itemId={} error={}",
                                sessionKey, runId, itemId, e.getMessage());
                        writeFailedManagedItem(sessionKey, runId, itemId, resolvedItemArtifactPath, e.getMessage(), eventCallback);
                    }
                    return workerResponse.toString();
                }, toolExecutionExecutor));
            }
            for (CompletableFuture<String> worker : workers) {
                worker.join();
            }
        }

        String handoffPrompt = buildManagedManifestHandoffPrompt(sessionKey, runId);
        if (handoffPrompt.isBlank()) {
            handoffPrompt = buildManagedManifestClosedHandoffPrompt(sessionKey, runId);
        }
        logger.info("managed skill runner complete session={} run={} attempts={} handoffChars={}",
                sessionKey, runId, attempts, handoffPrompt.length());
        return new ManagedRunnerResult(handoffPrompt, false);
    }

    private List<ActiveManifestRegistry.ActiveManifestState> selectManagedRunnerStates(
            ActiveManifestRegistry.ActiveManifestState state,
            int configuredParallelism) {
        if (state == null) {
            return List.of();
        }
        return List.of(state);
    }

    private ActiveManifestRegistry.ActiveManifestItem managedRunnerItem(ActiveManifestRegistry.ActiveManifestState state) {
        if (state == null) {
            return null;
        }
        return state.runningItem() != null ? state.runningItem() : state.nextPendingItem();
    }

    private void ensureManagedItemStarted(String sessionKey,
                                          String runId,
                                          ActiveManifestRegistry.ActiveManifestItem item,
                                          Consumer<ExecutionEvent> eventCallback) {
        if (item == null || item.id().isBlank() || "running".equalsIgnoreCase(item.status())) {
            return;
        }
        callFrameworkManifest(sessionKey, runId, eventCallback, "start", item.id(), null, null, "managed runner started item");
    }

    private void closeManagedItemFromDeclaredArtifact(String sessionKey,
                                                      String runId,
                                                      String itemId,
                                                      String itemArtifactPath,
                                                      Consumer<ExecutionEvent> eventCallback) {
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        var state = activeManifestRegistry.findManagedBlockingState(sessionKey, runId).orElse(null);
        if (state == null || state.runningItem() == null || !itemId.equals(state.runningItem().id())) {
            return;
        }
        if (itemArtifactReady(itemArtifactPath)) {
            callFrameworkManifest(
                    sessionKey,
                    runId,
                    eventCallback,
                    "done",
                    itemId,
                    null,
                    itemArtifactPath,
                    "managed runner item artifact ready"
            );
            return;
        }
        String reason = itemArtifactPath == null || itemArtifactPath.isBlank()
                ? "Managed runner item did not declare an itemArtifactPath in the active skill runtime"
                : "Managed runner item artifact was not created or is empty: " + itemArtifactPath;
        callFrameworkManifest(
                sessionKey,
                runId,
                eventCallback,
                "fail",
                itemId,
                reason,
                itemArtifactPath,
                null
        );
    }

    private void closeManagedItemFromModelResult(String sessionKey,
                                                 String runId,
                                                 String itemId,
                                                 String itemArtifactPath,
                                                 String modelResult,
                                                 Consumer<ExecutionEvent> eventCallback) {
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        if (itemArtifactPath == null || itemArtifactPath.isBlank()) {
            writeFailedManagedItem(sessionKey, runId, itemId, itemArtifactPath,
                    "managed runner itemResultPathTemplate did not render a path", eventCallback);
            return;
        }
        ManagedItemOutput output = ManagedItemOutput.from(activeSkillRegistry.managedRunnerItemOutput(sessionKey, runId));
        String content = extractManagedItemContent(output, modelResult);
        if (content.isBlank()) {
            writeFailedManagedItem(sessionKey, runId, itemId, itemArtifactPath,
                    "managed runner model did not return " + output.contractName() + " content", eventCallback);
            return;
        }
        if (output == ManagedItemOutput.JSON_OBJECT) {
            try {
                JsonNode parsed = OBJECT_MAPPER.readTree(content);
                if (!parsed.isObject()) {
                    writeFailedManagedItem(sessionKey, runId, itemId, itemArtifactPath,
                            "managed runner model returned non-object JSON", eventCallback);
                    return;
                }
                List<String> missingColumns = missingSchemaColumns(sessionKey, runId, parsed);
                if (!missingColumns.isEmpty()) {
                    writeFailedManagedItem(sessionKey, runId, itemId, itemArtifactPath,
                            "managed runner JSON missing required schema field(s): " + String.join(", ", missingColumns),
                            eventCallback);
                    return;
                }
                content = OBJECT_MAPPER.writeValueAsString(parsed);
            } catch (Exception e) {
                writeFailedManagedItem(sessionKey, runId, itemId, itemArtifactPath,
                        "managed runner returned invalid JSON: " + e.getMessage(), eventCallback);
                return;
            }
        }
        writeManagedItemArtifacts(sessionKey, runId, itemId, itemArtifactPath, output, content, eventCallback, true, "");
    }

    private String extractManagedItemContent(ManagedItemOutput output, String modelResult) {
        if (modelResult == null || modelResult.isBlank()) {
            return "";
        }
        return switch (output) {
            case JSON_OBJECT -> extractJsonObject(modelResult);
            case FILE_PATH -> extractFirstPath(modelResult);
            case TEXT, MARKDOWN -> stripOuterFence(modelResult).strip();
        };
    }

    private String stripOuterFence(String text) {
        String stripped = text == null ? "" : text.strip();
        if (!stripped.startsWith("```")) {
            return stripped;
        }
        return stripped.replaceFirst("(?s)^```[A-Za-z0-9_-]*\\s*", "")
                .replaceFirst("(?s)\\s*```\\s*$", "")
                .strip();
    }

    private String extractFirstPath(String text) {
        String stripped = stripOuterFence(text);
        for (String line : stripped.lines().toList()) {
            String candidate = line.replace("`", "").replace("\"", "").trim();
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private enum ManagedItemOutput {
        JSON_OBJECT("json_object"),
        TEXT("text"),
        MARKDOWN("markdown"),
        FILE_PATH("file_path");

        private final String contractName;

        ManagedItemOutput(String contractName) {
            this.contractName = contractName;
        }

        String contractName() {
            return contractName;
        }

        static ManagedItemOutput from(String value) {
            if (value == null || value.isBlank()) {
                return JSON_OBJECT;
            }
            String normalized = value.trim().toLowerCase().replace('-', '_');
            return switch (normalized) {
                case "text", "plain_text" -> TEXT;
                case "markdown", "md" -> MARKDOWN;
                case "file_path", "path" -> FILE_PATH;
                default -> JSON_OBJECT;
            };
        }
    }

    private boolean reuseExistingManagedItemArtifact(String sessionKey,
                                                    String runId,
                                                    Consumer<ExecutionEvent> eventCallback,
                                                    String itemId,
                                                    String itemArtifactPath) {
        if (!itemArtifactReady(itemArtifactPath)) {
            return false;
        }
        try {
            String content = Files.readString(Path.of(itemArtifactPath.trim()), StandardCharsets.UTF_8).trim();
            if (content.isBlank()) {
                return false;
            }
            ManagedItemOutput output = ManagedItemOutput.from(activeSkillRegistry.managedRunnerItemOutput(sessionKey, runId));
            if (output == ManagedItemOutput.JSON_OBJECT) {
                JsonNode parsed = OBJECT_MAPPER.readTree(extractJsonObject(content));
                if (!missingSchemaColumns(sessionKey, runId, parsed).isEmpty()) {
                    return false;
                }
                content = OBJECT_MAPPER.writeValueAsString(parsed);
            }
            writeManagedAggregate(sessionKey, runId, itemId, itemArtifactPath, output, content, true, "");
            callFrameworkManifest(
                    sessionKey,
                    runId,
                    eventCallback,
                    "done",
                    itemId,
                    null,
                    itemArtifactPath,
                    "managed runner reused existing item artifact"
            );
            return true;
        } catch (Exception e) {
            logger.warn("managed runner existing item artifact unusable session={} run={} itemId={} path={} error={}",
                    sessionKey, runId, itemId, itemArtifactPath, e.getMessage());
            return false;
        }
    }

    private void writeFailedManagedItem(String sessionKey,
                                        String runId,
                                        String itemId,
                                        String itemArtifactPath,
                                        String error,
                                        Consumer<ExecutionEvent> eventCallback) {
        String json;
        try {
            json = OBJECT_MAPPER.writeValueAsString(buildManagedFailureRow(sessionKey, runId, itemId, error));
        } catch (Exception e) {
            json = "{\"itemId\":\"" + safeJson(itemId) + "\",\"status\":\"failed\",\"error\":\"" + safeJson(error) + "\"}";
        }
        writeManagedItemArtifacts(sessionKey, runId, itemId, itemArtifactPath,
                ManagedItemOutput.JSON_OBJECT, json, eventCallback, false, error);
    }

    private void writeManagedItemArtifacts(String sessionKey,
                                           String runId,
                                           String itemId,
                                           String itemArtifactPath,
                                           ManagedItemOutput output,
                                           String json,
                                           Consumer<ExecutionEvent> eventCallback,
                                           boolean success,
                                           String error) {
        try {
            if (itemArtifactPath != null && !itemArtifactPath.isBlank()) {
                Path itemPath = Path.of(itemArtifactPath.trim());
                if (itemPath.getParent() != null) {
                    Files.createDirectories(itemPath.getParent());
                }
                Files.writeString(itemPath, json + System.lineSeparator(), StandardCharsets.UTF_8);
            }
            writeManagedAggregate(sessionKey, runId, itemId, itemArtifactPath, output, json, success, error);
            callFrameworkManifest(
                    sessionKey,
                    runId,
                    eventCallback,
                    success ? "done" : "fail",
                    itemId,
                    success ? null : error,
                    itemArtifactPath,
                    success ? "managed runner wrote item result" : "managed runner wrote failed item result"
            );
        } catch (Exception e) {
            logger.warn("managed runner failed to write item artifacts session={} run={} itemId={} path={} error={}",
                    sessionKey, runId, itemId, itemArtifactPath, e.getMessage());
            callFrameworkManifest(
                    sessionKey,
                    runId,
                    eventCallback,
                    "fail",
                    itemId,
                    e.getMessage(),
                    itemArtifactPath,
                    null
            );
        }
    }

    private void writeManagedAggregate(String sessionKey,
                                       String runId,
                                       String itemId,
                                       String itemArtifactPath,
                                       ManagedItemOutput output,
                                       String content,
                                       boolean success,
                                       String error) throws IOException {
        var state = activeManifestRegistry.findManagedBlockingState(sessionKey, runId).orElse(null);
        if (state == null || state.artifactPath().isBlank()) {
            return;
        }
        Path aggregate = Path.of(state.artifactPath().trim());
        if (aggregate.getParent() != null) {
            Files.createDirectories(aggregate.getParent());
        }
        String line = output == ManagedItemOutput.JSON_OBJECT && success
                ? content
                : OBJECT_MAPPER.writeValueAsString(buildManagedAggregateIndexRow(itemId, itemArtifactPath, output, success, error));
        Files.writeString(aggregate, line + System.lineSeparator(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private Map<String, Object> buildManagedAggregateIndexRow(String itemId,
                                                              String itemArtifactPath,
                                                              ManagedItemOutput output,
                                                              boolean success,
                                                              String error) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("itemId", itemId);
        row.put("status", success ? "success" : "failed");
        row.put("outputType", output.contractName());
        row.put("artifactPath", itemArtifactPath != null ? itemArtifactPath : "");
        if (!success) {
            row.put("error", error != null ? error : "unknown managed runner error");
        }
        return row;
    }

    private Map<String, Object> buildManagedFailureRow(String sessionKey, String runId, String itemId, String error) {
        Map<String, Object> row = new LinkedHashMap<>();
        var state = activeManifestRegistry.findManagedBlockingState(sessionKey, runId).orElse(null);
        List<String> columns = parseSchemaColumns(state != null ? state.schema() : "");
        if (columns.isEmpty()) {
            row.put("itemId", itemId);
            row.put("status", "failed");
            row.put("error", error != null ? error : "unknown managed runner error");
            return row;
        }
        for (String column : columns) {
            String lower = column.toLowerCase();
            if (column.contains("状态") || lower.equals("status")) {
                row.put(column, "failed");
            } else if (column.contains("错误") || lower.equals("error")) {
                row.put(column, error != null ? error : "unknown managed runner error");
            } else {
                row.put(column, "");
            }
        }
        return row;
    }

    private List<String> parseSchemaColumns(String schema) {
        if (schema == null || schema.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(schema);
            JsonNode columns = root.path("columns");
            if (!columns.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            columns.forEach(node -> {
                if (node.isTextual() && !node.asText().isBlank()) {
                    values.add(node.asText());
                }
            });
            return values;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> missingSchemaColumns(String sessionKey, String runId, JsonNode json) {
        if (json == null || !json.isObject()) {
            return List.of();
        }
        var state = activeManifestRegistry.findManagedBlockingState(sessionKey, runId).orElse(null);
        List<String> columns = parseSchemaColumns(state != null ? state.schema() : "");
        if (columns.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String column : columns) {
            if (!json.has(column)) {
                missing.add(column);
            }
        }
        return missing;
    }

    private String extractManagedItemArtifactPath(String runnerPrompt) {
        if (runnerPrompt == null || runnerPrompt.isBlank()) {
            return "";
        }
        var matcher = java.util.regex.Pattern
                .compile("(?im)^\\s*(?:-\\s*)?(itemArtifactPath|item_artifact_path|resultPath|result_path)\\s*[:=]\\s*(.+?)\\s*$")
                .matcher(runnerPrompt);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(2)
                .replace("`", "")
                .replace("\"", "")
                .trim();
    }

    private boolean itemArtifactReady(String itemArtifactPath) {
        if (itemArtifactPath == null || itemArtifactPath.isBlank()) {
            return false;
        }
        try {
            Path path = Path.of(itemArtifactPath.trim());
            return Files.isRegularFile(path) && Files.size(path) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private ToolCallback[] filterManagedRunnerTools(ToolCallback[] tools, String sessionKey, String runId) {
        if (tools == null) {
            return new ToolCallback[0];
        }
        List<String> allowedTools = activeSkillRegistry.managedRunnerAllowedTools(sessionKey, runId);
        return Arrays.stream(tools)
                .filter(callback -> callback != null && callback.getToolDefinition() != null)
                .filter(callback -> isManagedRunnerToolAllowed(callback.getToolDefinition().name(), allowedTools))
                .toArray(ToolCallback[]::new);
    }

    private boolean isManagedRunnerToolAllowed(String name, List<String> allowedTools) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String normalized = name.trim().toLowerCase();
        if (allowedTools != null && !allowedTools.isEmpty()) {
            return allowedTools.stream()
                    .filter(tool -> tool != null && !tool.isBlank())
                    .map(tool -> tool.trim().toLowerCase())
                    .anyMatch(normalized::equals);
        }
        return normalized.startsWith("read_")
                || "list_dir".equals(normalized)
                || "context_ref".equals(normalized)
                || "web_fetch".equals(normalized)
                || "web_search".equals(normalized);
    }

    private String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String stripped = text.strip();
        if (stripped.startsWith("```")) {
            stripped = stripped.replaceFirst("(?s)^```(?:json)?\\s*", "")
                    .replaceFirst("(?s)\\s*```\\s*$", "")
                    .strip();
        }
        int start = stripped.indexOf('{');
        int end = stripped.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return stripped.substring(start, end + 1).strip();
    }

    private String safeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void callFrameworkManifest(String sessionKey,
                                       String runId,
                                       Consumer<ExecutionEvent> eventCallback,
                                       String action,
                                       String itemId,
                                       String error,
                                       String artifactPath,
                                       String note) {
        ToolCallback manifestCallback = Arrays.stream(allToolCallbacks)
                .filter(callback -> callback != null
                        && callback.getToolDefinition() != null
                        && "manifest".equalsIgnoreCase(callback.getToolDefinition().name()))
                .findFirst()
                .orElse(null);
        if (manifestCallback == null) {
            logger.warn("managed runner cannot update manifest because manifest tool is unavailable session={} run={} action={} itemId={}",
                    sessionKey, runId, action, itemId);
            return;
        }
        AgentExecutionContext.ExecutionScope previousScope = AgentExecutionContext.getCurrentScope();
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                sessionKey,
                eventCallback,
                runId,
                null,
                null,
                null,
                null
        ));
        var request = OBJECT_MAPPER.createObjectNode();
        try {
            request.put("action", action);
            activeManifestRegistry.findManagedBlockingState(sessionKey, runId)
                    .map(ActiveManifestRegistry.ActiveManifestState::manifestId)
                    .filter(manifestId -> !manifestId.isBlank())
                    .ifPresent(manifestId -> request.put("manifestId", manifestId));
            request.put("itemId", itemId);
            if (error != null && !error.isBlank()) {
                request.put("error", error);
            }
            if (artifactPath != null && !artifactPath.isBlank()) {
                request.put("artifactPath", artifactPath);
            }
            if (note != null && !note.isBlank()) {
                request.put("note", note);
            }
            ToolExecutionResult result = toolRuntime.execute(new ToolExecutionRequest(
                    sessionKey,
                    "manifest",
                    OBJECT_MAPPER.writeValueAsString(request),
                    manifestCallback,
                    eventCallback
            ));
            if (!result.success() || isToolErrorResponse(result.response())) {
                logger.warn("managed runner manifest update failed session={} run={} action={} itemId={} response={}",
                        sessionKey, runId, action, itemId, result.response());
            }
        } catch (Exception e) {
            logger.warn("managed runner manifest update threw session={} run={} action={} itemId={} error={}",
                    sessionKey, runId, action, itemId, e.getMessage());
        } finally {
            if (previousScope != null) {
                AgentExecutionContext.setCurrentContext(previousScope);
            } else {
                AgentExecutionContext.clear();
            }
        }
    }

    private List<Message> buildManagedRunnerMessages(String systemPrompt,
                                                     String originalUserContent,
                                                     String managedPrompt) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        StringBuilder user = new StringBuilder();
        user.append("JOBCLAW_MANAGED_RUNNER\n");
        user.append("The main agent flow is waiting while this managed runner advances one manifest unit with short context.\n");
        user.append("Use only the current managed card, current tool results, and the original user request below. Do not rely on previous item outputs in conversation.\n\n");
        if (originalUserContent != null && !originalUserContent.isBlank()) {
            user.append("Original user request:\n").append(originalUserContent).append("\n\n");
        }
        user.append(managedPrompt);
        messages.add(new UserMessage(user.toString()));
        return messages;
    }

    private record ManagedRunnerResult(String message, boolean error) {
    }

    private String runModelAttempt(ExecutionClientBundle executionClientBundle,
                                   List<Message> promptMessages,
                                   ToolCallback[] tools,
                                   OpenAiChatOptions options,
                                   String sessionKey,
                                   Consumer<ExecutionEvent> eventCallback,
                                   StringBuilder fullResponse,
                                   boolean checkpointAssistantDraft) {
        StringBuilder attemptResponse = new StringBuilder();
        StreamDeltaNormalizer streamDeltaNormalizer = new StreamDeltaNormalizer();
        ReasoningContentFilter reasoningContentFilter = ReasoningContentFilter.forModel(
                isReasoningSideChannelProvider(executionClientBundle.providerName(), executionClientBundle.model()));
        int fullResponseStartLength = fullResponse != null ? fullResponse.length() : 0;
        AtomicInteger lastDraftCheckpoint = new AtomicInteger(fullResponse != null ? fullResponse.length() : 0);

        if (shouldDisableDeepSeekThinking(executionClientBundle.providerName(), executionClientBundle.model())) {
            try {
                String content = executionClientBundle.chatClient().prompt()
                        .messages(promptMessages)
                        .toolCallbacks(tools)
                        .options(options)
                        .call()
                        .content();
                String delta = reasoningContentFilter.sanitizeFinal(content != null ? content : "");
                if (!delta.isEmpty()) {
                    attemptResponse.append(delta);
                    fullResponse.append(delta);
                    if (checkpointAssistantDraft) {
                        sessionManager.saveAssistantDraft(sessionKey, fullResponse.toString());
                    }
                    if (eventCallback != null) {
                        eventCallback.accept(new ExecutionEvent(
                                sessionKey,
                                ExecutionEvent.EventType.THINK_STREAM,
                                delta
                        ));
                    }
                }
                return attemptResponse.toString();
            } catch (RuntimeException e) {
                if (containsManagedManifestTakeoverSignal(e)) {
                    logger.info("managed manifest takeover session={} attemptChars={}", sessionKey, attemptResponse.length());
                    return attemptResponse.toString();
                }
                WebClientResponseException responseException = findWebClientResponseException(e);
                if (responseException != null) {
                    logger.warn("LLM HTTP error session={} status={} body={}",
                            sessionKey,
                            responseException.getStatusCode(),
                            responseException.getResponseBodyAsString());
                }
                throw e;
            }
        }

        Flux<String> contentStream = executionClientBundle.chatClient().prompt()
                .messages(promptMessages)
                .toolCallbacks(tools)
                .options(options)
                .stream()
                .content();

        try {
            contentStream.toStream().forEach(content -> {
                if (content != null && !content.isEmpty()) {
                    String delta = streamDeltaNormalizer.normalize(attemptResponse, content);
                    if (delta.isEmpty()) {
                        return;
                    }
                    delta = reasoningContentFilter.filterDelta(delta);
                    if (delta.isEmpty()) {
                        return;
                    }
                    attemptResponse.append(delta);
                    fullResponse.append(delta);
                    if (checkpointAssistantDraft
                            && fullResponse.length() - lastDraftCheckpoint.get() >= ASSISTANT_DRAFT_CHECKPOINT_CHARS) {
                        sessionManager.saveAssistantDraft(sessionKey, fullResponse.toString());
                        lastDraftCheckpoint.set(fullResponse.length());
                    }
                    if (eventCallback != null) {
                        if (toolExecutionStateTracker.isExecuting(sessionKey)) {
                            toolExecutionStateTracker.bufferThink(sessionKey, delta);
                        } else {
                            eventCallback.accept(new ExecutionEvent(
                                    sessionKey,
                                    ExecutionEvent.EventType.THINK_STREAM,
                                    delta
                            ));
                        }
                    }
                }
            });
        } catch (RuntimeException e) {
            if (containsManagedManifestTakeoverSignal(e)) {
                logger.info("managed manifest takeover session={} attemptChars={}", sessionKey, attemptResponse.length());
                return attemptResponse.toString();
            }
            WebClientResponseException responseException = findWebClientResponseException(e);
            if (responseException != null) {
                logger.warn("LLM HTTP error session={} status={} body={}",
                        sessionKey,
                        responseException.getStatusCode(),
                        responseException.getResponseBodyAsString());
            }
            throw e;
        }
        String sanitized = reasoningContentFilter.sanitizeFinal(attemptResponse.toString());
        if (sanitized.length() != attemptResponse.length()) {
            attemptResponse.setLength(0);
            attemptResponse.append(sanitized);
            if (fullResponse != null && fullResponse.length() >= fullResponseStartLength) {
                fullResponse.setLength(fullResponseStartLength);
                fullResponse.append(sanitized);
            }
        }
        return sanitized;
    }

    private boolean containsManagedManifestTakeoverSignal(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ManagedManifestTakeoverSignal) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private WebClientResponseException findWebClientResponseException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof WebClientResponseException responseException) {
                return responseException;
            }
            current = current.getCause();
        }
        return null;
    }

    private boolean shouldReturnManagedManifestControl(String toolName, String request, ToolExecutionResult result) {
        if (!"manifest".equalsIgnoreCase(toolName) || result == null || !result.success()) {
            return false;
        }
        if (isToolErrorResponse(result.response())) {
            return false;
        }
        AgentExecutionContext.ExecutionScope scope = AgentExecutionContext.getCurrentScope();
        if (scope == null) {
            return false;
        }
        String action = extractManifestAction(request);
        if (!("create".equals(action) || "done".equals(action) || "fail".equals(action) || "failed".equals(action))) {
            return false;
        }
        return activeManifestRegistry.findManagedBlockingState(scope.sessionKey(), scope.runId()).isPresent()
                || activeManifestRegistry.findManagedHandoffState(scope.sessionKey(), scope.runId()).isPresent();
    }

    private String completeManagedManifestRequest(String toolName,
                                                  String request,
                                                  AgentExecutionContext.ExecutionScope scope) {
        if (!"manifest".equalsIgnoreCase(toolName) || scope == null || request == null || request.isBlank()) {
            return request;
        }
        String action = extractManifestAction(request);
        if (!("start".equals(action) || "done".equals(action) || "fail".equals(action) || "failed".equals(action))) {
            return request;
        }
        var state = activeManifestRegistry.findManagedBlockingState(scope.sessionKey(), scope.runId()).orElse(null);
        if (state == null) {
            return request;
        }
        ActiveManifestRegistry.ActiveManifestItem currentItem = state.runningItem() != null
                ? state.runningItem()
                : state.nextPendingItem();
        if (currentItem == null || currentItem.id().isBlank()) {
            return request;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(request);
            if (!node.isObject()) {
                return request;
            }
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            boolean changed = false;
            if (isBlankText(object.get("manifestId")) && !state.manifestId().isBlank()) {
                object.put("manifestId", state.manifestId());
                changed = true;
            }
            if (isBlankText(object.get("itemId"))) {
                object.put("itemId", currentItem.id());
                changed = true;
            }
            if (!changed) {
                return request;
            }
            String completed = OBJECT_MAPPER.writeValueAsString(object);
            logger.info("managed manifest request completed session={} run={} action={} manifestId={} itemId={}",
                    scope.sessionKey(), scope.runId(), action, state.manifestId(), currentItem.id());
            return completed;
        } catch (Exception e) {
            return request;
        }
    }

    private boolean isBlankText(JsonNode node) {
        return node == null || node.isNull() || (node.isTextual() && node.asText("").isBlank());
    }

    private boolean isToolErrorResponse(String response) {
        return response != null && response.stripLeading().startsWith("Error:");
    }

    private String extractManifestAction(String request) {
        if (request == null || request.isBlank()) {
            return "";
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(request);
            JsonNode action = node.get("action");
            if (action != null && action.isTextual()) {
                return action.asText("").trim().toLowerCase();
            }
        } catch (Exception ignored) {
            // Fall back to a loose text match for non-JSON tool requests.
        }
        String normalized = request.toLowerCase();
        for (String action : List.of("create", "done", "failed", "fail")) {
            if (normalized.contains("\"action\":\"" + action + "\"")
                    || normalized.contains("\"action\": \"" + action + "\"")
                    || normalized.contains("\"action\" : \"" + action + "\"")
                    || normalized.contains("action=" + action)
                    || normalized.contains("action: " + action)) {
                return action;
            }
        }
        return "";
    }

    private static class ManagedManifestTakeoverSignal extends RuntimeException {
        ManagedManifestTakeoverSignal() {
            super(MANAGED_MANIFEST_TAKEOVER_REASON, null, false, false);
        }
    }

    /**
     * 处理消息（带回调，使用默认 Agent 定义）
     *
     * @param sessionKey    会话密钥
     * @param userContent   用户输入内容
     * @param eventCallback 执行事件回调
     * @return Agent 响应
     */
    public String process(String sessionKey, String userContent, Consumer<ExecutionEvent> eventCallback) {
        return processWithDefinition(sessionKey, userContent, null, eventCallback);
    }

    /**
     * 处理消息（带工具调用和角色指定，支持回调）
     *
     * @param sessionKey    会话密钥
     * @param userContent   用户输入内容
     * @param role          Agent 角色（可选，null 表示使用默认角色）
     * @param eventCallback 执行事件回调
     * @return Agent 响应
     */
    public String process(String sessionKey, String userContent, AgentRole role,
                          Consumer<ExecutionEvent> eventCallback) {
        return processWithDefinition(sessionKey, userContent, role != null ? AgentDefinition.fromRole(role) : null,
                eventCallback);
    }

    /**
     * 构建系统提示（使用 ContextBuilder）
     *
     * @param sessionKey    会话键
     * @param currentMessage 当前消息
     * @return 系统提示
     */
    private String buildSystemPrompt(String sessionKey, String currentMessage) {
        return contextBuilder.buildSystemPrompt(sessionKey, currentMessage);
    }

    private List<Message> buildPromptMessages(String systemPrompt,
                                              List<io.jobclaw.providers.Message> historyMessages,
                                              String currentContent) {
        List<Message> promptMessages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            promptMessages.add(new SystemMessage(systemPrompt));
        }
        if (historyMessages != null) {
            for (io.jobclaw.providers.Message message : historyMessages) {
                Message springMessage = toSpringMessage(message);
                if (springMessage != null) {
                    promptMessages.add(springMessage);
                }
            }
        }
        promptMessages.add(new UserMessage(currentContent));
        return promptMessages;
    }

    private Message toSpringMessage(io.jobclaw.providers.Message message) {
        if (message == null || message.getRole() == null) {
            return null;
        }

        String role = message.getRole();
        String content = message.getContent() != null ? message.getContent() : "";
        if ("assistant".equals(message.getRole())
                && isReasoningSideChannelProvider(defaultProviderConfig.providerName(), model)) {
            content = ReasoningContentFilter.sanitizeText(content);
        }
        return switch (role) {
            case "system" -> new SystemMessage(content);
            case "assistant" -> toSpringAssistantMessage(message, content);
            case "tool" -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            message.getToolCallId() != null ? message.getToolCallId() : "tool",
                            message.getToolCallId() != null ? message.getToolCallId() : "tool",
                            content
                    )))
                    .build();
            default -> new UserMessage(content);
        };
    }

    private Message toSpringAssistantMessage(io.jobclaw.providers.Message message, String content) {
        if (message.getToolCalls() == null || message.getToolCalls().isEmpty()) {
            return new AssistantMessage(content);
        }
        List<AssistantMessage.ToolCall> toolCalls = message.getToolCalls().stream()
                .filter(toolCall -> toolCall != null && toolCall.getFunction() != null)
                .map(toolCall -> new AssistantMessage.ToolCall(
                        toolCall.getId(),
                        toolCall.getType() != null ? toolCall.getType() : "function",
                        toolCall.getFunction().getName(),
                        toolCall.getFunction().getArguments()
                ))
                .toList();
        return AssistantMessage.builder()
                .content(content)
                .toolCalls(toolCalls)
                .build();
    }

    /**
     * 构建系统提示
     */
    private String buildSystemPrompt() {
        return buildSystemPromptWithRole(null);
    }

    /**
     * 构建带角色的系统提示
     *
     * @param role Agent 角色（null 表示默认角色）
     * @return 系统提示
     */
    private String buildSystemPromptWithRole(AgentRole role) {
        return buildSystemPromptWithDefinition("role:default", null,
                role != null ? AgentDefinition.fromRole(role) : null);
    }

    /**
     * 构建带 Agent 定义的系统提示
     *
     * @param definition Agent 定义（null 表示默认配置）
     * @return 系统提示
     */
    private String buildSystemPromptWithDefinition(String sessionKey,
                                                   String currentMessage,
                                                   AgentDefinition definition) {
        String basePrompt = buildSystemPrompt(sessionKey, currentMessage);
        if (definition == null) {
            return basePrompt;
        }

        StringBuilder sb = new StringBuilder(basePrompt);
        sb.append("\n\n---\n\n");
        sb.append("# Agent Overlay\n\n");
        sb.append("This execution is running as a specialized agent.\n\n");
        sb.append("## Agent Identity\n");
        sb.append("- Name: ").append(definition.getDisplayName()).append("\n");
        sb.append("- Code: ").append(definition.getCode()).append("\n\n");

        if (definition.getDescription() != null && !definition.getDescription().isBlank()) {
            sb.append("## Agent Description\n");
            sb.append(definition.getDescription()).append("\n\n");
        }

        if (definition.getSystemPrompt() != null && !definition.getSystemPrompt().isBlank()) {
            sb.append("## Agent Instructions\n");
            sb.append(definition.getSystemPrompt()).append("\n\n");
        }

        if (definition.getAllowedTools() != null && !definition.getAllowedTools().isEmpty()) {
            sb.append("## Tool Restrictions\n");
            sb.append("You are only allowed to use: ");
            sb.append(String.join(", ", definition.getAllowedTools()));
            sb.append("\n\n");
        } else {
            sb.append("## Tool Restrictions\n");
            sb.append("No agent-specific tool override is set. Reuse the main assistant toolset.\n\n");
        }

        if (definition.getAllowedSkills() != null && !definition.getAllowedSkills().isEmpty()) {
            sb.append("## Skill Restrictions\n");
            sb.append("You are only allowed to use: ");
            sb.append(String.join(", ", definition.getAllowedSkills()));
            sb.append("\n\n");
        }

        sb.append("## Execution Rules\n");
        sb.append("1. Reuse the main assistant runtime policy, memory policy, and context rules unless the agent overlay narrows them.\n");
        sb.append("2. If this is a built-in role agent or a persistent saved agent, prefer following the overlay rather than inventing a third temporary execution pattern.\n");
        sb.append("3. When no specific saved agent is requested, sub-agent execution should default to the main assistant configuration plus the selected role overlay.\n");
        return sb.toString();
    }

    /**
     * 包装 ToolCallback，在执行时发布 TOOL_START、TOOL_OUTPUT、TOOL_ERROR 事件。
     *
     * @param rawCallbacks  原始 ToolCallback 数组
     * @param sessionKey    当前会话 key
     * @param eventCallback 事件回调（可为 null）
     * @return 包装后的 ToolCallback 数组
     */
    private ToolCallback[] wrapToolCallbacks(ToolCallback[] rawCallbacks,
                                             String sessionKey,
                                             Consumer<ExecutionEvent> eventCallback) {
        if (rawCallbacks == null) {
            return rawCallbacks;
        }

        logger.info("工具事件追踪功能：包装 {} 个工具回调以支持事件追踪", rawCallbacks.length);

        // 包装每个 ToolCallback，使其在执行时发布事件
        return java.util.Arrays.stream(rawCallbacks)
                .map(callback -> wrapSingleCallback(callback, sessionKey, eventCallback))
                .toArray(ToolCallback[]::new);
    }

    /**
     * 包装单个 ToolCallback，在工具执行时发布事件
     *
     * @param callback 原始 ToolCallback
     * @param sessionKey 会话 key
     * @param eventCallback 事件回调
     * @return 包装后的 ToolCallback
     */
    private ToolCallback wrapSingleCallback(ToolCallback callback,
                                            String sessionKey,
                                            Consumer<ExecutionEvent> eventCallback) {
        AgentExecutionContext.ExecutionScope capturedScope = AgentExecutionContext.getCurrentScope();
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return callback.getToolDefinition();
            }

            @Override
            public String call(String request) {
                AgentExecutionContext.ExecutionScope previousScope = AgentExecutionContext.getCurrentScope();
                if (capturedScope != null) {
                    AgentExecutionContext.setCurrentContext(capturedScope);
                }
                try {
                    String effectiveRequest = completeManagedManifestRequest(
                            getToolDefinition().name(),
                            request,
                            capturedScope != null ? capturedScope : AgentExecutionContext.getCurrentScope()
                    );
                    ToolExecutionResult result = toolRuntime.execute(new ToolExecutionRequest(
                            sessionKey,
                            getToolDefinition().name(),
                            effectiveRequest,
                            callback,
                            eventCallback
                    ));
                    if (shouldReturnManagedManifestControl(getToolDefinition().name(), effectiveRequest, result)) {
                        logger.info("managed manifest tool boundary reached session={} run={} tool={} request={}",
                                capturedScope != null ? capturedScope.sessionKey() : sessionKey,
                                capturedScope != null ? capturedScope.runId() : null,
                                getToolDefinition().name(),
                                effectiveRequest != null ? effectiveRequest : "");
                        throw new ManagedManifestTakeoverSignal();
                    }
                    return result.response();
                } finally {
                    if (previousScope != null) {
                        AgentExecutionContext.setCurrentContext(previousScope);
                    } else {
                        AgentExecutionContext.clear();
                    }
                }
            }
        };
    }

    /**
     * 根据 Agent 定义过滤工具
     *
     * @param definition Agent 定义
     * @return 过滤后的工具数组
     */
    private ToolCallback[] filterToolsByDefinition(AgentDefinition definition, String userContent) {
        if (definition != null && definition.getAllowedTools() != null && !definition.getAllowedTools().isEmpty()) {
            ToolCallback[] explicitTools = Arrays.stream(allToolCallbacks)
                    .filter(tool -> definition.isToolAllowed(tool.getToolDefinition().name()))
                    .toArray(ToolCallback[]::new);
            logger.debug("Using explicit agent tool allowlist for {}: {}", definition.getCode(), toolNames(explicitTools));
            return explicitTools;
        }

        logger.debug("No explicit agent tool allowlist; using full toolset: {}", toolNames(allToolCallbacks));
        return allToolCallbacks;
    }

    private boolean containsInterruptedException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }

    private List<String> toolNames(ToolCallback[] callbacks) {
        if (callbacks == null) {
            return List.of();
        }
        return Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    static String normalizeStreamDelta(CharSequence currentResponse, String nextChunk) {
        return new StreamDeltaNormalizer().normalize(currentResponse, nextChunk);
    }

    static final class StreamDeltaNormalizer {
        private StreamMode mode = StreamMode.UNKNOWN;

        String normalize(CharSequence currentResponse, String nextChunk) {
            if (nextChunk == null || nextChunk.isEmpty()) {
                return "";
            }
            String current = currentResponse == null ? "" : currentResponse.toString();
            if (current.isEmpty()) {
                return nextChunk;
            }
            if (current.endsWith(nextChunk)) {
                return "";
            }
            if (mode == StreamMode.CUMULATIVE) {
                return nextChunk.startsWith(current) ? nextChunk.substring(current.length()) : nextChunk;
            }
            if (mode == StreamMode.DELTA) {
                return nextChunk;
            }
            if (nextChunk.startsWith(current)) {
                mode = StreamMode.CUMULATIVE;
                return nextChunk.substring(current.length());
            }
            mode = StreamMode.DELTA;
            return nextChunk;
        }
    }

    enum StreamMode {
        UNKNOWN,
        DELTA,
        CUMULATIVE
    }

    /**
     * 获取 Agent 状态
     */
    public String getStatus() {
        return "Spring AI initialized (model: " + config.getAgent().getModel() + ")";
    }

    private OpenAiChatOptions buildExecutionOptions(AgentDefinition definition, String baseModel) {
        return buildExecutionOptions(definition, baseModel, defaultProviderConfig.providerName());
    }

    private OpenAiChatOptions buildExecutionOptions(AgentDefinition definition, String baseModel, String providerName) {
        String effectiveModel = baseModel != null && !baseModel.isBlank() ? baseModel : model;
        Integer effectiveMaxTokens = null;
        Double effectiveTemperature = null;

        if (definition != null && definition.getConfig() != null) {
            AgentDefinition.AgentConfig definitionConfig = definition.getConfig();
            if (definitionConfig.getModel() != null && !definitionConfig.getModel().isBlank()) {
                effectiveModel = definitionConfig.getModel();
            }
            if (definitionConfig.getMaxTokens() != null && definitionConfig.getMaxTokens() > 0) {
                effectiveMaxTokens = definitionConfig.getMaxTokens();
            }
            if (definitionConfig.getTemperature() != null) {
                effectiveTemperature = definitionConfig.getTemperature();
            }
        }

        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(effectiveModel);
        if (effectiveMaxTokens != null) {
            builder.maxTokens(effectiveMaxTokens);
        }
        if (effectiveTemperature != null) {
            builder.temperature(effectiveTemperature);
        }
        if (shouldDisableDeepSeekThinking(providerName, effectiveModel)) {
            builder.extraBody(Map.of("thinking", Map.of("type", "disabled")));
        }
        return builder.build();
    }

    private boolean shouldDisableDeepSeekThinking(String providerName, String modelName) {
        if (!isReasoningSideChannelProvider(providerName, modelName)) {
            return false;
        }
        String modelValue = modelName != null ? modelName.toLowerCase() : "";
        return !modelValue.contains("reasoner");
    }

    private OpenAiChatOptions deepSeekThinkingDisabledOptions(String providerName, String modelName) {
        if (!shouldDisableDeepSeekThinking(providerName, modelName)) {
            return null;
        }
        return OpenAiChatOptions.builder()
                .model(modelName)
                .extraBody(Map.of("thinking", Map.of("type", "disabled")))
                .build();
    }

    private ExecutionClientBundle createExecutionClientBundle(AgentDefinition definition) {
        AgentDefinition.AgentConfig definitionConfig = definition != null ? definition.getConfig() : null;
        String providerOverride = definitionConfig != null ? definitionConfig.getProvider() : null;
        String apiBaseOverride = definitionConfig != null ? definitionConfig.getApiBase() : null;
        String modelOverride = definitionConfig != null ? definitionConfig.getModel() : null;

        if ((providerOverride == null || providerOverride.isBlank())
                && (apiBaseOverride == null || apiBaseOverride.isBlank())
                && (modelOverride == null || modelOverride.isBlank())) {
            return new ExecutionClientBundle(chatClient, model, defaultProviderConfig.providerName());
        }

        ResolvedProviderConfig resolved = providerRuntime.resolve(
                config,
                providerOverride,
                apiBaseOverride,
                modelOverride
        );
        OpenAiApi openAiApi = createOpenAiApi(resolved);
        OpenAiChatModel.Builder chatModelBuilder = OpenAiChatModel.builder()
                .openAiApi(openAiApi);
        OpenAiChatOptions defaultOptions = deepSeekThinkingDisabledOptions(resolved.providerName(), resolved.model());
        if (defaultOptions != null) {
            chatModelBuilder.defaultOptions(defaultOptions);
        }
        OpenAiChatModel chatModel = chatModelBuilder.build();
        ChatClient executionChatClient = ChatClient.builder(chatModel).build();
        return new ExecutionClientBundle(executionChatClient, resolved.model(), resolved.providerName());
    }

    private record ExecutionClientBundle(ChatClient chatClient, String model, String providerName) {
    }

    private boolean isReasoningSideChannelProvider(String providerName, String modelName) {
        String provider = providerName != null ? providerName.toLowerCase() : "";
        String modelValue = modelName != null ? modelName.toLowerCase() : "";
        return provider.contains("deepseek") || modelValue.contains("deepseek");
    }

    private static final class ReasoningContentFilter {
        private final boolean enabled;
        private boolean inThinkBlock;

        private ReasoningContentFilter(boolean enabled) {
            this.enabled = enabled;
        }

        static ReasoningContentFilter forModel(boolean enabled) {
            return new ReasoningContentFilter(enabled);
        }

        String filterDelta(String delta) {
            if (!enabled || delta == null || delta.isEmpty()) {
                return delta != null ? delta : "";
            }
            StringBuilder visible = new StringBuilder(delta.length());
            int index = 0;
            while (index < delta.length()) {
                if (inThinkBlock) {
                    int close = indexOfIgnoreCase(delta, "</think>", index);
                    if (close < 0) {
                        return visible.toString();
                    }
                    inThinkBlock = false;
                    index = close + "</think>".length();
                    continue;
                }
                int open = indexOfIgnoreCase(delta, "<think>", index);
                if (open < 0) {
                    visible.append(delta, index, delta.length());
                    break;
                }
                visible.append(delta, index, open);
                inThinkBlock = true;
                index = open + "<think>".length();
            }
            return visible.toString();
        }

        String sanitizeFinal(String text) {
            if (!enabled) {
                return text != null ? text : "";
            }
            return sanitizeText(text);
        }

        static String sanitizeText(String text) {
            if (text == null || text.isEmpty()) {
                return "";
            }
            String sanitized = text.replaceAll("(?is)<think>.*?</think>", "");
            sanitized = sanitized.replaceAll("(?im)^\\s*\"?reasoning_content\"?\\s*:\\s*\".*\"\\s*,?\\s*$", "");
            return sanitized;
        }

        private static int indexOfIgnoreCase(String source, String target, int fromIndex) {
            int max = source.length() - target.length();
            for (int i = Math.max(0, fromIndex); i <= max; i++) {
                if (source.regionMatches(true, i, target, 0, target.length())) {
                    return i;
                }
            }
            return -1;
        }
    }

    private OpenAiApi createOpenAiApi(ResolvedProviderConfig resolvedProvider) {
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(openAiRequestFactory());
        if (shouldDisableDeepSeekThinking(resolvedProvider.providerName(), resolvedProvider.model())) {
            restClientBuilder.requestInterceptor(deepSeekThinkingDisabledInterceptor());
        }
        return OpenAiApi.builder()
                .apiKey(resolvedProvider.apiKey())
                .baseUrl(resolvedProvider.springAiBaseUrl())
                .restClientBuilder(restClientBuilder)
                .build();
    }

    private ClientHttpRequestInterceptor deepSeekThinkingDisabledInterceptor() {
        return (request, body, execution) -> {
            byte[] patchedBody = body;
            if (body != null && body.length > 0) {
                try {
                    JsonNode root = OBJECT_MAPPER.readTree(body);
                    if (root instanceof ObjectNode objectNode && !objectNode.has("thinking")) {
                        ObjectNode thinking = OBJECT_MAPPER.createObjectNode();
                        thinking.put("type", "disabled");
                        objectNode.set("thinking", thinking);
                        patchedBody = OBJECT_MAPPER.writeValueAsBytes(objectNode);
                    }
                } catch (Exception e) {
                    logger.debug("failed to inject DeepSeek thinking disabled flag: {}", e.getMessage());
                }
            }
            return execution.execute(request, patchedBody);
        };
    }

    private SimpleClientHttpRequestFactory openAiRequestFactory() {
        int timeoutMillis = safeTimeoutMillis(config.getAgent().getLlmCallTimeoutSeconds(), 300);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        return requestFactory;
    }

    private int safeTimeoutMillis(int seconds, int fallbackSeconds) {
        long effectiveSeconds = seconds > 0 ? seconds : fallbackSeconds;
        long millis = effectiveSeconds * 1_000L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1_000L, millis));
    }
    
    /**
     * 调用 LLM 生成响应（用于摘要生成，不带工具调用）。
     *
     * @param prompt  提示词
     * @param options 已保留兼容旧调用；辅助 LLM 调用不透传模型参数
     * @return LLM 响应
     */
    private AgentExecutionContext.ExecutionScope createExecutionScope(String sessionKey,
                                                                      AgentDefinition definition,
                                                                      Consumer<ExecutionEvent> eventCallback,
                                                                      AgentExecutionContext.ExecutionScope previousScope) {
        String agentId = definition != null ? definition.getCode() : "assistant";
        String agentName = definition != null ? definition.getDisplayName() : "Assistant";
        Consumer<ExecutionEvent> effectiveCallback = eventCallback != null
                ? eventCallback
                : previousScope != null ? previousScope.eventCallback() : null;

        if (previousScope != null && sessionKey.equals(previousScope.sessionKey()) && previousScope.runId() != null) {
            return new AgentExecutionContext.ExecutionScope(
                    sessionKey,
                    effectiveCallback,
                    previousScope.runId(),
                    previousScope.parentRunId(),
                    agentId,
                    agentName,
                    definition != null ? definition : previousScope.definition()
            );
        }

        String runId = previousScope != null ? AgentRunIds.newChildRunId() : AgentRunIds.newTopLevelRunId();
        String parentRunId = previousScope != null ? previousScope.runId() : null;
        return new AgentExecutionContext.ExecutionScope(
                sessionKey,
                effectiveCallback,
                runId,
                parentRunId,
                agentId,
                agentName,
                definition != null ? definition : previousScope != null ? previousScope.definition() : null
        );
    }

    public String callLLM(String prompt, Map<String, Object> options) {
        try {
            String response = simpleChatClient.prompt()
                    .system("You are a helpful assistant.")
                    .user(prompt)
                    .options(OpenAiChatOptions.builder().model(model).build())
                    .call()
                    .content();

            return response != null ? response : "";

        } catch (Exception e) {
            logger.error("LLM call failed: {}", e.getMessage());
            return "";
        }
    }
}
