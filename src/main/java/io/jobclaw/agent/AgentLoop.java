package io.jobclaw.agent;

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
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * AgentLoop - 基于 Spring AI 重构（使用 OpenAI 兼容模式支持 DashScope Coding Plan）
 */
@Component
public class AgentLoop {

    private static final Logger logger = LoggerFactory.getLogger(AgentLoop.class);

    private final Config config;
    private final SessionManager sessionManager;
    private final ChatClient chatClient;
    private final FileTools fileTools;
    private final String model;

    public AgentLoop(Config config, SessionManager sessionManager, FileTools fileTools) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.fileTools = fileTools;

        // 从配置获取 API Key、模型和 API 地址
        String apiKey = config.getProviders().getDashscope().getApiKey();
        this.model = config.getAgent().getModel();
        String apiBase = config.getProviders().getDashscope().getApiBase();
        
        // Spring AI OpenAI 兼容模式会自动追加/v1，所以去掉配置中的/v1 后缀
        String baseUrlForSpringAi = apiBase != null ? apiBase.replaceAll("/v1$", "") : null;

        logger.info("Spring AI OpenAI Compatible config - apiKey: {}***, model: {}, apiBase: {} -> using: {}", 
            apiKey != null && apiKey.length() > 4 ? apiKey.substring(0, 4) : "null", 
            model, apiBase, baseUrlForSpringAi);

        // 创建 OpenAI API 客户端（支持自定义 baseUrl，兼容 DashScope Coding Plan）
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrlForSpringAi)
                .build();

        // 创建 ChatModel
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .build();

        // 创建 ChatClient
        this.chatClient = ChatClient.builder(chatModel).build();

        logger.info("AgentLoop initialized with Spring AI OpenAI Compatible (model: {})", model);
    }

    /**
     * 处理消息（带工具调用）
     */
    public String process(String sessionKey, String userContent) {
        try {
            Session session = sessionManager.getOrCreate(sessionKey);

            logger.info("Calling Spring AI with model: {}...", config.getAgent().getModel());
            long startTime = System.currentTimeMillis();

            // 构建系统提示
            String systemPrompt = buildSystemPrompt();

            // 创建工具回调
            ToolCallback[] tools = MethodToolCallbackProvider.builder()
                    .toolObjects(fileTools)
                    .build()
                    .getToolCallbacks();

            // 调用 ChatClient（带工具）- 在 prompt 时指定模型，限制最大工具调用次数
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(model)
                    .maxTokens(2000)
                    .build();
            
            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userContent)
                    .toolCallbacks(tools)
                    .options(options)
                    .call()
                    .content();

            long elapsed = System.currentTimeMillis() - startTime;
            logger.info("Agent response received in {}ms", elapsed);

            // 保存会话历史
            session.addMessage("user", userContent);
            session.addMessage("assistant", response);
            sessionManager.save(session);

            logger.debug("Processed message for session {}", sessionKey);

            return response;

        } catch (Exception e) {
            logger.error("Error processing message for session {}", sessionKey, e);
            return "Error: " + e.getMessage() + " (check network/API key)";
        }
    }

    /**
     * 构建系统提示
     */
    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();

        sb.append("# JobClaw Agent\n\n");
        sb.append("You are a helpful AI assistant powered by JobClaw framework.\n\n");

        sb.append("## Tools Available\n");
        sb.append("- **read_file**: Read the contents of a file (path: required)\n");
        sb.append("- **write_file**: Write content to a file (path: required, content: required)\n");
        sb.append("- **list_dir**: List the contents of a directory (path: required)\n");
        sb.append("\n\n");

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
     * 获取 Agent 状态
     */
    public String getStatus() {
        return "Spring AI initialized (model: " + config.getAgent().getModel() + ")";
    }
}
