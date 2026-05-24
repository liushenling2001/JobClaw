package io.jobclaw.agent;

import io.jobclaw.config.Config;
import io.jobclaw.agent.AgentLoop;
import io.jobclaw.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final AgentLoop agentLoop;

    // 缓存的 Agent 实例池（按 sessionKey 复用）
    private final Map<String, AgentLoop> agentPool;

    public AgentRegistry(Config config, SessionManager sessionManager, AgentLoop agentLoop) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.agentLoop = agentLoop;
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
        // 直接复用 Spring 注入的 agentLoop 实例
        // 注意：AgentLoop 现在是单例 Bean，不再按 sessionKey 创建多个实例
        // sessionKey 在 process 方法中用于区分不同会话
        return agentLoop;
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
