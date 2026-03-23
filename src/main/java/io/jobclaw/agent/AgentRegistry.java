package io.jobclaw.agent;

import io.jobclaw.config.Config;
import io.jobclaw.session.SessionManager;
import io.jobclaw.tools.FileTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 注册表
 * 
 * 管理多个 Agent 实例，支持按需创建和复用
 */
@Component
public class AgentRegistry {

    private static final Logger logger = LoggerFactory.getLogger(AgentRegistry.class);

    private final Config config;
    private final SessionManager sessionManager;
    private final FileTools fileTools;
    
    // 缓存的 Agent 实例池
    private final Map<String, AgentLoop> agentPool;

    public AgentRegistry(Config config, SessionManager sessionManager, FileTools fileTools) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.fileTools = fileTools;
        this.agentPool = new ConcurrentHashMap<>();
        
        logger.info("AgentRegistry initialized");
    }

    /**
     * 获取或创建指定角色的 Agent
     * 
     * @param role Agent 角色
     * @param sessionKey 会话密钥
     * @return AgentLoop 实例
     */
    public AgentLoop getOrCreateAgent(AgentRole role, String sessionKey) {
        return getOrCreateAgent(AgentDefinition.fromRole(role), sessionKey);
    }

    /**
     * 获取或创建指定 Agent 定义的 Agent
     * 
     * @param definition Agent 定义
     * @param sessionKey 会话密钥
     * @return AgentLoop 实例
     */
    public AgentLoop getOrCreateAgent(AgentDefinition definition, String sessionKey) {
        String key = buildAgentKey(definition, sessionKey);
        
        return agentPool.computeIfAbsent(key, k -> {
            logger.info("Creating new Agent instance: {} (definition: {})", key, definition.getDisplayName());
            return createAgent(definition, sessionKey);
        });
    }

    /**
     * 创建新的 Agent 实例
     * 
     * @param role Agent 角色
     * @param sessionKey 会话密钥
     * @return AgentLoop 实例
     * @deprecated Use {@link #createAgent(AgentDefinition, String)} instead
     */
    @Deprecated
    private AgentLoop createAgent(AgentRole role, String sessionKey) {
        return createAgent(AgentDefinition.fromRole(role), sessionKey);
    }

    /**
     * 创建新的 Agent 实例
     * 
     * @param definition Agent 定义
     * @param sessionKey 会话密钥
     * @return AgentLoop 实例
     */
    private AgentLoop createAgent(AgentDefinition definition, String sessionKey) {
        try {
            // 从配置获取 API Key、模型和 API 地址
            String apiKey = config.getProviders().getDashscope().getApiKey();
            String model = config.getAgent().getModel();
            String apiBase = config.getProviders().getDashscope().getApiBase();
            
            // Spring AI OpenAI 兼容模式会自动追加/v1，所以去掉配置中的/v1 后缀
            String baseUrlForSpringAi = apiBase != null ? apiBase.replaceAll("/v1$", "") : null;

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
            ChatClient chatClient = ChatClient.builder(chatModel).build();

            // 创建 AgentLoop（使用构造函数注入）
            AgentLoop agent = new AgentLoop(config, sessionManager, fileTools, chatClient, model);
            
            logger.debug("Agent instance created successfully");
            return agent;
            
        } catch (Exception e) {
            logger.error("Failed to create Agent instance", e);
            throw new RuntimeException("Failed to create Agent instance: " + e.getMessage(), e);
        }
    }

    /**
     * 构建 Agent 缓存键
     * 
     * @param role Agent 角色
     * @param sessionKey 会话密钥
     * @return 缓存键
     */
    private String buildAgentKey(AgentRole role, String sessionKey) {
        return buildAgentKey(AgentDefinition.fromRole(role), sessionKey);
    }

    /**
     * 构建 Agent 缓存键
     * 
     * @param definition Agent 定义
     * @param sessionKey 会话密钥
     * @return 缓存键
     */
    private String buildAgentKey(AgentDefinition definition, String sessionKey) {
        return sessionKey + ":" + definition.getCode();
    }

    /**
     * 清除指定会话的所有 Agent 缓存
     * 
     * @param sessionKey 会话密钥
     */
    public void clearSessionAgents(String sessionKey) {
        agentPool.entrySet().removeIf(entry -> entry.getKey().startsWith(sessionKey + ":"));
        logger.debug("Cleared agent pool for session: {}", sessionKey);
    }

    /**
     * 清除所有 Agent 缓存
     */
    public void clearAll() {
        agentPool.clear();
        logger.info("Cleared all agent pool");
    }

    /**
     * 获取 Agent 池大小
     * 
     * @return 池中的 Agent 数量
     */
    public int getPoolSize() {
        return agentPool.size();
    }

    /**
     * 获取 Agent 池状态信息
     * 
     * @return 状态描述
     */
    public String getPoolStatus() {
        if (agentPool.isEmpty()) {
            return "Agent pool is empty";
        }
        
        StringBuilder sb = new StringBuilder("Agent pool status:\n");
        sb.append("Total agents: ").append(agentPool.size()).append("\n");
        
        // 按角色统计
        Map<String, Long> roleCount = agentPool.keySet().stream()
                .map(key -> key.substring(key.lastIndexOf(':') + 1))
                .collect(java.util.stream.Collectors.groupingBy(
                        role -> role,
                        java.util.stream.Collectors.counting()
                ));
        
        roleCount.forEach((role, count) -> 
            sb.append("  - ").append(role).append(": ").append(count).append("\n")
        );
        
        return sb.toString();
    }
}
