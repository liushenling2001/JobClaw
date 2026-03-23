package io.jobclaw.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 团队 - 管理多个子智能体
 * 
 * 功能：
 * - 动态创建/销毁子智能体
 * - 子智能体状态管理
 * - 团队记忆共享
 * - 任务分配和协调
 */
public class AgentTeam {

    private static final Logger logger = LoggerFactory.getLogger(AgentTeam.class);

    private final String teamId;
    private final String teamName;
    private final AgentDefinition masterAgent;
    private final Map<String, AgentDefinition> subAgents;
    private final List<String> sharedMemory;
    private final String sessionKey;

    /**
     * 创建 Agent 团队
     * 
     * @param teamId 团队 ID
     * @param teamName 团队名称
     * @param masterAgent 主智能体定义
     * @param sessionKey 会话密钥
     */
    public AgentTeam(String teamId, String teamName, AgentDefinition masterAgent, String sessionKey) {
        this.teamId = teamId;
        this.teamName = teamName;
        this.masterAgent = masterAgent;
        this.sessionKey = sessionKey;
        this.subAgents = new ConcurrentHashMap<>();
        this.sharedMemory = new ArrayList<>();
        
        logger.info("AgentTeam created: {} (master: {})", teamName, masterAgent.getDisplayName());
    }

    /**
     * 动态添加子智能体
     * 
     * @param definition 子智能体定义
     * @return 子智能体代码
     */
    public String addSubAgent(AgentDefinition definition) {
        subAgents.put(definition.getCode(), definition);
        logger.info("Sub-agent added to team {}: {} ({})", 
            teamName, definition.getDisplayName(), definition.getCode());
        return definition.getCode();
    }

    /**
     * 动态移除子智能体
     * 
     * @param agentCode 子智能体代码
     * @return 是否成功移除
     */
    public boolean removeSubAgent(String agentCode) {
        AgentDefinition removed = subAgents.remove(agentCode);
        if (removed != null) {
            logger.info("Sub-agent removed from team {}: {} ({})", 
                teamName, removed.getDisplayName(), agentCode);
            return true;
        }
        return false;
    }

    /**
     * 获取子智能体定义
     * 
     * @param agentCode 子智能体代码
     * @return Agent 定义（不存在返回 null）
     */
    public AgentDefinition getSubAgent(String agentCode) {
        return subAgents.get(agentCode);
    }

    /**
     * 获取所有子智能体
     * 
     * @return 子智能体列表
     */
    public List<AgentDefinition> getAllSubAgents() {
        return new ArrayList<>(subAgents.values());
    }

    /**
     * 获取主智能体
     * 
     * @return 主智能体定义
     */
    public AgentDefinition getMasterAgent() {
        return masterAgent;
    }

    /**
     * 添加到共享记忆
     * 
     * @param memory 记忆内容
     */
    public void addToSharedMemory(String memory) {
        sharedMemory.add(memory);
        logger.debug("Memory added to team {}: {}", teamName, memory);
    }

    /**
     * 获取共享记忆
     * 
     * @return 共享记忆列表
     */
    public List<String> getSharedMemory() {
        return new ArrayList<>(sharedMemory);
    }

    /**
     * 清空共享记忆
     */
    public void clearSharedMemory() {
        sharedMemory.clear();
        logger.info("Shared memory cleared for team {}", teamName);
    }

    /**
     * 获取团队 ID
     */
    public String getTeamId() {
        return teamId;
    }

    /**
     * 获取团队名称
     */
    public String getTeamName() {
        return teamName;
    }

    /**
     * 获取会话密钥
     */
    public String getSessionKey() {
        return sessionKey;
    }

    /**
     * 获取子智能体数量
     * 
     * @return 子智能体数量
     */
    public int getSubAgentCount() {
        return subAgents.size();
    }

    /**
     * 检查是否包含某个子智能体
     * 
     * @param agentCode 子智能体代码
     * @return 是否包含
     */
    public boolean hasSubAgent(String agentCode) {
        return subAgents.containsKey(agentCode);
    }

    /**
     * 获取团队状态
     * 
     * @return 状态描述
     */
    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("AgentTeam: ").append(teamName).append(" (").append(teamId).append(")\n");
        sb.append("  Master: ").append(masterAgent.getDisplayName()).append("\n");
        sb.append("  Sub-agents: ").append(subAgents.size()).append("\n");
        
        for (AgentDefinition agent : subAgents.values()) {
            sb.append("    - ").append(agent.getDisplayName())
              .append(" (").append(agent.getCode()).append(")\n");
        }
        
        sb.append("  Shared memories: ").append(sharedMemory.size()).append("\n");
        
        return sb.toString();
    }

    @Override
    public String toString() {
        return "AgentTeam{" +
                "teamId='" + teamId + '\'' +
                ", teamName='" + teamName + '\'' +
                ", masterAgent='" + masterAgent.getDisplayName() + '\'' +
                ", subAgents=" + subAgents.size() +
                '}';
    }
}
