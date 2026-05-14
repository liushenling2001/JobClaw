package io.jobclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
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
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .build();

        // 创建 ChatClient（带工具调用）
        this.chatClient = chatClient != null ? chatClient : ChatClient.builder(chatModel).build();

        // 创建简单的 ChatClient（用于摘要生成，不带工具调用）
        OpenAiChatModel simpleChatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .build();
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
            OpenAiChatOptions options = buildExecutionOptions(definition, executionClientBundle.model());

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
            for (ActiveManifestRegistry.ActiveManifestState runnerState : runnerStates) {
                String runnerPrompt = buildManagedManifestContinuationPrompt(sessionKey, runId, runnerState);
                if (runnerPrompt.isBlank()) {
                    continue;
                }
                workers.add(CompletableFuture.supplyAsync(() -> {
                    StringBuilder workerResponse = new StringBuilder();
                    List<Message> runnerMessages = buildManagedRunnerMessages(systemPrompt, originalUserContent, runnerPrompt);
                    runModelAttempt(
                            executionClientBundle,
                            runnerMessages,
                            tools,
                            options,
                            sessionKey,
                            eventCallback,
                            workerResponse,
                            false
                    );
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
        if (state.running() > 0 || configuredParallelism <= 1) {
            return List.of(state);
        }
        int limit = Math.max(1, Math.min(4, configuredParallelism));
        List<ActiveManifestRegistry.ActiveManifestItem> pendingItems = state.pendingQueue().stream()
                .limit(limit)
                .toList();
        if (pendingItems.isEmpty()) {
            return List.of(state);
        }
        return pendingItems.stream()
                .map(state::withNextPendingItem)
                .toList();
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
        AtomicInteger lastDraftCheckpoint = new AtomicInteger(fullResponse != null ? fullResponse.length() : 0);
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
            throw e;
        }
        return attemptResponse.toString();
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

    private boolean shouldReturnManagedManifestControl(String toolName, String request, ToolExecutionResult result) {
        if (!"manifest".equalsIgnoreCase(toolName) || result == null || !result.success()) {
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
                    ToolExecutionResult result = toolRuntime.execute(new ToolExecutionRequest(
                            sessionKey,
                            getToolDefinition().name(),
                            request,
                            callback,
                            eventCallback
                    ));
                    if (shouldReturnManagedManifestControl(getToolDefinition().name(), request, result)) {
                        logger.info("managed manifest tool boundary reached session={} run={} tool={} request={}",
                                capturedScope != null ? capturedScope.sessionKey() : sessionKey,
                                capturedScope != null ? capturedScope.runId() : null,
                                getToolDefinition().name(),
                                request != null ? request : "");
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
        return builder.build();
    }

    private ExecutionClientBundle createExecutionClientBundle(AgentDefinition definition) {
        AgentDefinition.AgentConfig definitionConfig = definition != null ? definition.getConfig() : null;
        String providerOverride = definitionConfig != null ? definitionConfig.getProvider() : null;
        String apiBaseOverride = definitionConfig != null ? definitionConfig.getApiBase() : null;
        String modelOverride = definitionConfig != null ? definitionConfig.getModel() : null;

        if ((providerOverride == null || providerOverride.isBlank())
                && (apiBaseOverride == null || apiBaseOverride.isBlank())
                && (modelOverride == null || modelOverride.isBlank())) {
            return new ExecutionClientBundle(chatClient, model);
        }

        ResolvedProviderConfig resolved = providerRuntime.resolve(
                config,
                providerOverride,
                apiBaseOverride,
                modelOverride
        );
        OpenAiApi openAiApi = createOpenAiApi(resolved);
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .build();
        ChatClient executionChatClient = ChatClient.builder(chatModel).build();
        return new ExecutionClientBundle(executionChatClient, resolved.model());
    }

    private record ExecutionClientBundle(ChatClient chatClient, String model) {
    }

    private OpenAiApi createOpenAiApi(ResolvedProviderConfig resolvedProvider) {
        return OpenAiApi.builder()
                .apiKey(resolvedProvider.apiKey())
                .baseUrl(resolvedProvider.springAiBaseUrl())
                .restClientBuilder(RestClient.builder().requestFactory(openAiRequestFactory()))
                .build();
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
