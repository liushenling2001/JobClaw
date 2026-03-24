package io.jobclaw.agent;

import io.jobclaw.agent.evolution.MemoryEvolver;
import io.jobclaw.agent.evolution.MemoryStore;
import io.jobclaw.config.Config;
import io.jobclaw.session.Session;
import io.jobclaw.session.SessionManager;
import io.jobclaw.tools.FileTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final FileTools fileTools;
    private final String model;

    // 新增组件
    private final ContextBuilder contextBuilder;
    private final SessionSummarizer sessionSummarizer;

    // 无工具调用的专用 ChatClient（用于摘要生成）
    private final ChatClient simpleChatClient;

    public AgentLoop(Config config, SessionManager sessionManager, FileTools fileTools) {
        this(config, sessionManager, fileTools, null, null);
    }

    /**
     * 构造 AgentLoop，初始化所有组件。
     */
    public AgentLoop(Config config, SessionManager sessionManager, FileTools fileTools,
                     ChatClient chatClient, String model) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.fileTools = fileTools;

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

        // 初始化 ContextBuilder
        this.contextBuilder = new ContextBuilder(config, sessionManager,
                new io.jobclaw.tools.ToolRegistry());

        // 设置上下文窗口
        this.contextBuilder.setContextWindow(config.getAgent().getMaxTokens());

        // 获取记忆存储
        MemoryStore memoryStore = contextBuilder.getMemoryStore();

        // 初始化记忆进化引擎
        MemoryEvolver memoryEvolver = new MemoryEvolver(memoryStore, this, this.model);

        // 初始化会话摘要器
        this.sessionSummarizer = new SessionSummarizer(
                sessionManager,
                this,
                config.getAgent().getMaxTokens(),
                memoryStore,
                memoryEvolver
        );

        logger.info("AgentLoop initialized with Spring AI OpenAI Compatible (model: {})", this.model);
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
                    buildSystemPromptWithDefinition(definition) : buildSystemPrompt(sessionKey, userContent);

            // 创建工具回调（支持工具过滤）
            ToolCallback[] tools = filterToolsByDefinition(definition);

            // 调用 ChatClient（带工具）- 使用配置中的 maxTokens 和 temperature
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(model)
                    .maxTokens(config.getAgent().getMaxTokens())
                    .temperature(config.getAgent().getTemperature())
                    .build();

            // 使用流式响应获取 LLM 回复
            StringBuilder fullResponse = new StringBuilder();

            Flux<String> contentStream = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userContent)
                    .toolCallbacks(tools)
                    .options(options)
                    .stream()
                    .content();

            // 阻塞等待流完成，同时收集所有响应内容
            contentStream.toStream().forEach(content -> {
                if (content != null && !content.isEmpty()) {
                    fullResponse.append(content);
                    // 实时推送 LLM 返回的真实内容
                    if (eventCallback != null) {
                        eventCallback.accept(new ExecutionEvent(
                                sessionKey,
                                ExecutionEvent.EventType.THINK_STREAM,
                                content  // 这是 LLM 实时返回的内容片段
                        ));
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
            session.addMessage("user", userContent);
            session.addMessage("assistant", response);
            sessionManager.save(session);

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
        return buildSystemPromptWithDefinition(role != null ? AgentDefinition.fromRole(role) : null);
    }

    /**
     * 构建带 Agent 定义的系统提示
     *
     * @param definition Agent 定义（null 表示默认配置）
     * @return 系统提示
     */
    private String buildSystemPromptWithDefinition(AgentDefinition definition) {
        StringBuilder sb = new StringBuilder();

        if (definition != null) {
            sb.append("# JobClaw Agent - ").append(definition.getDisplayName()).append("\n\n");
            sb.append(definition.getSystemPrompt()).append("\n\n");
        } else {
            sb.append("# JobClaw Agent\n\n");
            sb.append("You are a helpful AI assistant powered by JobClaw framework.\n\n");
        }

        sb.append("## Tools Available\n");
        sb.append("- **read_file**: Read the contents of a file (path: required)\n");
        sb.append("- **write_file**: Write content to a file (path: required, content: required)\n");
        sb.append("- **list_dir**: List the contents of a directory (path: required)\n");
        sb.append("\n\n");

        if (definition != null && definition.getAllowedTools() != null && !definition.getAllowedTools().isEmpty()) {
            sb.append("## Tool Restrictions\n");
            sb.append("You are only allowed to use the following tools: ");
            sb.append(String.join(", ", definition.getAllowedTools()));
            sb.append("\n\n");
        }

        if (definition != null && definition.getAllowedSkills() != null && !definition.getAllowedSkills().isEmpty()) {
            sb.append("## Skill Restrictions\n");
            sb.append("You are only allowed to use the following skills: ");
            sb.append(String.join(", ", definition.getAllowedSkills()));
            sb.append("\n\n");
        }

        sb.append("## Important Rules\n");
        sb.append("1. **Think before acting**: Only use tools when necessary. If you can answer from your knowledge, do so directly.\n");
        sb.append("2. **Avoid loops**: After using a tool 2-3 times, synthesize the information and provide a final answer.\n");
        sb.append("3. **Be concise**: Don't call the same tool repeatedly with the same parameters.\n");
        sb.append("4. **Know when to stop**: Once you have enough information to answer the question, stop calling tools and respond directly.\n");
        sb.append("5. **Final answer required**: Always end with a clear, direct response to the user's question.\n");
        sb.append("\n\n");

        sb.append("## Response Format\n");
        sb.append("- Use tools strategically to gather information\n");
        sb.append("- After gathering information, provide a comprehensive answer in the user's language\n");
        sb.append("- Do not mention tool usage in your final response unless specifically asked\n");
        sb.append("\n\n");

        sb.append("## Current Session\n");
        sb.append("Time: ").append(Instant.now()).append("\n");

        return sb.toString();
    }

    /**
     * 根据 Agent 定义过滤工具
     *
     * @param definition Agent 定义
     * @return 过滤后的工具数组
     */
    private ToolCallback[] filterToolsByDefinition(AgentDefinition definition) {
        // 获取所有工具
        ToolCallback[] allTools = MethodToolCallbackProvider.builder()
                .toolObjects(fileTools)
                .build()
                .getToolCallbacks();

        // 如果没有定义或没有限制，返回所有工具
        if (definition == null || definition.getAllowedTools() == null || definition.getAllowedTools().isEmpty()) {
            return allTools;
        }

        // 过滤工具
        return java.util.Arrays.stream(allTools)
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
     * 调用 LLM 生成响应（用于摘要生成，不带工具调用）。
     *
     * @param prompt  提示词
     * @param options 选项（max_tokens, temperature 等）
     * @return LLM 响应
     */
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
