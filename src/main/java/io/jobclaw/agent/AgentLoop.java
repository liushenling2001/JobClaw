package io.jobclaw.agent;

import io.jobclaw.agent.evolution.MemoryEvolver;
import io.jobclaw.agent.evolution.MemoryStore;
import io.jobclaw.agent.runtime.AgentRunIds;
import io.jobclaw.context.ContextAssembler;
import io.jobclaw.context.ContextAssemblyOptions;
import io.jobclaw.context.ContextAssemblyPolicy;
import io.jobclaw.config.Config;
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
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
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

    // 无工具调用的专用 ChatClient（用于摘要生成）
    private final ChatClient simpleChatClient;

    // 工具调用期间的 THINK_STREAM 缓冲区（按 sessionKey 分组）
    private final Map<String, Queue<String>> thinkStreamBuffer;

    // 工具执行状态跟踪（独立的布尔状态，精确跟踪每个 session 是否在工具执行中）
    private final Map<String, Boolean> toolExecutingState;

    public AgentLoop(Config config, SessionManager sessionManager,
                     ToolCallback[] allToolCallbacks,
                     ContextBuilder contextBuilder,
                     ContextAssembler contextAssembler,
                     ContextAssemblyPolicy contextAssemblyPolicy,
                     SummaryService summaryService) {
        this(config, sessionManager, allToolCallbacks, contextBuilder, contextAssembler, contextAssemblyPolicy,
                summaryService, null, null);
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
        this.config = config;
        this.sessionManager = sessionManager;
        this.allToolCallbacks = allToolCallbacks;

        // 从配置获取 API Key、模型和 API 地址
        String apiKey = config.getProviders().getDashscope().getApiKey();
        this.model = model != null ? model : config.getAgent().getModel();
        String apiBase = config.getProviders().getDashscope().getApiBase();

        // 如果 apiBase 为空，使用默认值
        if (apiBase == null || apiBase.isEmpty()) {
            apiBase = "https://dashscope.aliyuncs.com/compatible-mode/v1";
            logger.warn("apiBase not configured, using default: {}", apiBase);
        }

        // Spring AI OpenAI 兼容模式会自动追加/v1，所以去掉配置中的/v1 后缀
        String baseUrlForSpringAi = apiBase.replaceAll("/v1$", "");

        logger.info("Spring AI OpenAI Compatible config - apiKey: {}***, model: {}, apiBase: {} -> using: {}",
                apiKey != null && apiKey.length() > 4 ? apiKey.substring(0, 4) : "null",
                this.model, apiBase, baseUrlForSpringAi);

        // 创建 OpenAI API 客户端（支持自定义 baseUrl，兼容 DashScope Coding Plan）
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrlForSpringAi)
                .build();

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
        this.thinkStreamBuffer = new ConcurrentHashMap<>();
        this.toolExecutingState = new ConcurrentHashMap<>();

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

        try {
            Session session = sessionManager.getOrCreate(sessionKey);

            logger.info("Calling Spring AI with model: {}...", config.getAgent().getModel());
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
            ToolCallback[] rawTools = filterToolsByDefinition(definition);
            ToolCallback[] tools = wrapToolCallbacks(rawTools, sessionKey, eventCallback);

            // 调用 ChatClient（带工具）- 使用配置中的 maxTokens 和 temperature
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(model)
                    .maxTokens(config.getAgent().getMaxTokens())
                    .temperature(config.getAgent().getTemperature())
                    .build();

            // 使用结构化上下文装配器，保留消息边界，不再拍平成单段文本
            ContextAssemblyOptions assemblyOptions = contextAssemblyPolicy.buildOptions(sessionKey, userContent);
            List<io.jobclaw.providers.Message> historyMessages =
                    contextAssembler.assemble(sessionKey, userContent, assemblyOptions);
            List<Message> promptMessages = buildPromptMessages(systemPrompt, historyMessages, userContent);

            // 使用流式响应获取 LLM 回复
            StringBuilder fullResponse = new StringBuilder();

            Flux<String> contentStream = chatClient.prompt()
                    .messages(promptMessages)
                    .toolCallbacks(tools)
                    .options(options)
                    .stream()
                    .content();

            // 阻塞等待流完成，同时收集所有响应内容
            contentStream.toStream().forEach(content -> {
                if (content != null && !content.isEmpty()) {
                    fullResponse.append(content);
                    if (eventCallback != null) {
                        if (isToolExecuting(sessionKey)) {
                            bufferThinkStream(sessionKey, content);
                        } else {
                            eventCallback.accept(new ExecutionEvent(
                                    sessionKey,
                                    ExecutionEvent.EventType.THINK_STREAM,
                                    content
                            ));
                        }
                    }
                }
            });

            long elapsed = System.currentTimeMillis() - startTime;
            logger.info("Agent response received in {}ms", elapsed);

            String response = fullResponse.toString();

            // 发布思考结束事件
            if (eventCallback != null) {
                eventCallback.accept(new ExecutionEvent(sessionKey, ExecutionEvent.EventType.THINK_END,
                        "思考完成，耗时：" + elapsed + "ms"));
                eventCallback.accept(new ExecutionEvent(sessionKey, ExecutionEvent.EventType.FINAL_RESPONSE,
                        response));
            }

            // 保存会话历史
            sessionManager.addMessage(sessionKey, "user", userContent);
            sessionManager.addMessage(sessionKey, "assistant", response);

            // 触发会话摘要检查
            sessionSummarizer.maybeSummarize(sessionKey);

            logger.debug("Processed message for session {} (agent: {})", sessionKey,
                    definition != null ? definition.getDisplayName() : "default");

            return response;

        } catch (Exception e) {
            logger.error("Error processing message for session {}", sessionKey, e);
            if (eventCallback != null) {
                eventCallback.accept(new ExecutionEvent(sessionKey, ExecutionEvent.EventType.ERROR,
                        "Error: " + e.getMessage()));
            }
            return "Error: " + e.getMessage() + " (check network/API key)";
        } finally {
            // 清理执行上下文
            if (previousScope != null) {
                AgentExecutionContext.setCurrentContext(previousScope);
            } else {
                AgentExecutionContext.clear();
            }
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
            case "assistant" -> new AssistantMessage(content);
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
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return callback.getToolDefinition();
            }

            @Override
            public String call(String request) {
                String toolName = getToolDefinition().name();
                String toolId = toolName + "_" + System.currentTimeMillis();

                // 构建 metadata（使用 Map.of 节省内存）
                Map<String, Object> startMetadata = Map.of(
                    "toolName", toolName,
                    "toolId", toolId,
                    "request", truncateToolRequest(request)
                );

                io.jobclaw.providers.Message assistantToolMessage =
                        io.jobclaw.providers.Message.assistant("");
                assistantToolMessage.setToolCalls(List.of(
                        new io.jobclaw.providers.ToolCall(toolId, toolName, request)
                ));
                sessionManager.addFullMessage(sessionKey, assistantToolMessage);

                // 标记工具执行中，THINK_STREAM 需要缓冲
                setToolExecuting(sessionKey, true);

                // 发布 TOOL_START 事件（包含 metadata）
                if (eventCallback != null) eventCallback.accept(new ExecutionEvent(
                    sessionKey,
                    ExecutionEvent.EventType.TOOL_START,
                    "正在调用工具：" + toolName ,
                    startMetadata
                ));

                try {
                    // 调用原始工具
                    String response = callback.call(request);
                    sessionManager.addFullMessage(sessionKey,
                            io.jobclaw.providers.Message.tool(toolId, response));

                    // 构建 metadata（使用 Map.of 节省内存）
                    Map<String, Object> endMetadata = Map.of(
                        "toolName", toolName,
                        "toolId", toolId,
                        "request", truncateToolRequest(request)
                    );
                    
                    // 发布 TOOL_END 事件（包含 metadata）
                    if (eventCallback != null) eventCallback.accept(new ExecutionEvent(
                        sessionKey,
                        ExecutionEvent.EventType.TOOL_END,
                        "工具调用完成：" + toolName,
                        endMetadata
                    ));
                    
                    // 限制工具返回结果的长度
                    String resultData = truncateOutput(response, toolName);
                    
                    // 发布 TOOL_OUTPUT 事件（显示工具返回结果，包含 metadata）
                    if (eventCallback != null) eventCallback.accept(new ExecutionEvent(
                        sessionKey,
                        ExecutionEvent.EventType.TOOL_OUTPUT,
                        resultData,
                        endMetadata  // 使用相同的 metadata
                    ));
                    
                    // 工具调用完成，标记为非执行中
                    setToolExecuting(sessionKey, false);
                    
                    // 刷新缓冲的 THINK_STREAM
                    if (eventCallback != null) {
                        flushThinkStreamBuffer(sessionKey, eventCallback);
                    } else {
                        clearThinkStreamBuffer(sessionKey);
                    }
                    
                    return response;
                    
                } catch (Exception e) {
                    // 构建 error metadata（使用 Map.of 节省内存）
                    sessionManager.addFullMessage(sessionKey,
                            io.jobclaw.providers.Message.tool(toolId, "ERROR: " + e.getMessage()));
                    Map<String, Object> errorMetadata = Map.of(
                        "toolName", toolName,
                        "toolId", toolId,
                        "request", truncateToolRequest(request)
                    );

                    // 发布 TOOL_ERROR 事件
                    if (eventCallback != null) eventCallback.accept(new ExecutionEvent(
                        sessionKey,
                        ExecutionEvent.EventType.TOOL_ERROR,
                        "工具执行失败：" + toolName + " - " + e.getMessage(),
                        errorMetadata
                    ));

                    // 工具调用失败，标记为非执行中
                    setToolExecuting(sessionKey, false);

                    // 清空缓冲（不再需要）
                    clearThinkStreamBuffer(sessionKey);

                    throw e;
                }
            }
            
            /**
             * 截断工具输出结果，避免超长反馈
             *
             * @param output 原始输出
             * @param toolName 工具名称
             * @return 截断后的输出（带提示信息）
             */
            private String truncateOutput(String output, String toolName) {
                if (output == null) {
                    return "无返回数据";
                }
                
                int maxLength = config.getAgent().getMaxToolOutputLength();
                
                if (output.length() <= maxLength) {
                    return output;
                }
                
                // 截断并添加提示信息
                String truncated = output.substring(0, maxLength);
                String truncateNotice = "\n\n[... 返回结果已截断，共 " + output.length() + 
                                       " 字符，显示前 " + maxLength + " 字符 ...]";
                
                logger.info("工具 {} 输出超长 ({} 字符)，已截断至 {} 字符", 
                           toolName, output.length(), maxLength);
                
                return truncated + truncateNotice;
            }

            private String truncateToolRequest(String request) {
                if (request == null || request.isBlank()) {
                    return "";
                }
                int maxLength = Math.min(500, config.getAgent().getMaxToolOutputLength());
                if (request.length() <= maxLength) {
                    return request;
                }
                return request.substring(0, maxLength) + "\n[request truncated]";
            }
        };
    }

    /**
     * 根据 Agent 定义过滤工具
     *
     * @param definition Agent 定义
     * @return 过滤后的工具数组
     */
    private ToolCallback[] filterToolsByDefinition(AgentDefinition definition) {
        // 如果没有定义或没有限制，返回所有工具
        if (definition == null || definition.getAllowedTools() == null || definition.getAllowedTools().isEmpty()) {
            return allToolCallbacks;
        }

        // 过滤工具
        return java.util.Arrays.stream(allToolCallbacks)
                .filter(tool -> definition.isToolAllowed(tool.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
    }

    /**
     * 获取 Agent 状态
     */
    public String getStatus() {
        return "Spring AI initialized (model: " + config.getAgent().getModel() + ")";
    }
    
    /**
     * 缓冲 THINK_STREAM 事件（工具执行期间）
     */
    private void bufferThinkStream(String sessionKey, String content) {
        thinkStreamBuffer.computeIfAbsent(sessionKey, k -> new LinkedList<>()).offer(content);
    }
    
    /**
     * 刷新缓冲的 THINK_STREAM 事件
     */
    private void flushThinkStreamBuffer(String sessionKey, Consumer<ExecutionEvent> eventCallback) {
        Queue<String> buffer = thinkStreamBuffer.get(sessionKey);
        if (buffer != null && !buffer.isEmpty()) {
            logger.debug("Flushing {} buffered THINK_STREAM events for session: {}", buffer.size(), sessionKey);
            String content;
            while ((content = buffer.poll()) != null) {
                eventCallback.accept(new ExecutionEvent(
                        sessionKey,
                        ExecutionEvent.EventType.THINK_STREAM,
                        content
                ));
            }
        }
    }
    
    /**
     * 清空 THINK_STREAM 缓冲区
     */
    private void clearThinkStreamBuffer(String sessionKey) {
        thinkStreamBuffer.remove(sessionKey);
    }
    
    /**
     * 检查是否正在执行工具
     */
    private boolean isToolExecuting(String sessionKey) {
        return toolExecutingState.getOrDefault(sessionKey, false);
    }

    /**
     * 设置工具执行状态
     */
    private void setToolExecuting(String sessionKey, boolean executing) {
        if (executing) {
            toolExecutingState.put(sessionKey, true);
            thinkStreamBuffer.computeIfAbsent(sessionKey, k -> new LinkedList<>());
        } else {
            toolExecutingState.put(sessionKey, false);
            toolExecutingState.remove(sessionKey);
        }
    }
    
    /**
     * 调用 LLM 生成响应（用于摘要生成，不带工具调用）。
     *
     * @param prompt  提示词
     * @param options 选项（max_tokens, temperature 等）
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
                    agentName
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
                agentName
        );
    }

    public String callLLM(String prompt, Map<String, Object> options) {
        try {
            OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder();

            if (options != null) {
                if (options.containsKey("max_tokens")) {
                    optionsBuilder.maxTokens((Integer) options.get("max_tokens"));
                }
                if (options.containsKey("temperature")) {
                    optionsBuilder.temperature((Double) options.get("temperature"));
                }
            }

            OpenAiChatOptions chatOptions = optionsBuilder.build();

            String response = simpleChatClient.prompt()
                    .system("You are a helpful assistant.")
                    .user(prompt)
                    .options(chatOptions)
                    .call()
                    .content();

            return response != null ? response : "";

        } catch (Exception e) {
            logger.error("LLM call failed: {}", e.getMessage());
            return "";
        }
    }
}
