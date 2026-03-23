package io.jobclaw.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 团队管理器
 * 
 * 功能：
 * - 创建和管理多个 Agent 团队
 * - 团队生命周期管理
 * - 团队状态监控
 */
@Component
public class AgentTeamManager {

    private static final Logger logger = LoggerFactory.getLogger(AgentTeamManager.class);

    private final Map<String, AgentTeam> teams;

    public AgentTeamManager() {
        this.teams = new ConcurrentHashMap<>();
        logger.info("AgentTeamManager initialized");
    }

    /**
     * 创建新的 Agent 团队
     * 
     * @param teamName 团队名称
     * @param masterAgent 主智能体定义
     * @param sessionKey 会话密钥
     * @return 创建的团队
     */
    public AgentTeam createTeam(String teamName, AgentDefinition masterAgent, String sessionKey) {
        String teamId = "team-" + UUID.randomUUID().toString().substring(0, 8);
        AgentTeam team = new AgentTeam(teamId, teamName, masterAgent, sessionKey);
        teams.put(teamId, team);
        logger.info("AgentTeam created: {} ({})", teamName, teamId);
        return team;
    }

    /**
     * 获取 Agent 团队
     * 
     * @param teamId 团队 ID
     * @return Agent 团队（不存在返回 null）
     */
    public AgentTeam getTeam(String teamId) {
        return teams.get(teamId);
    }

    /**
     * 销毁 Agent 团队
     * 
     * @param teamId 团队 ID
     * @return 是否成功销毁
     */
    public boolean destroyTeam(String teamId) {
        AgentTeam removed = teams.remove(teamId);
        if (removed != null) {
            logger.info("AgentTeam destroyed: {} ({})", removed.getTeamName(), teamId);
            return true;
        }
        return false;
    }

    /**
     * 获取所有团队
     * 
     * @return 团队列表
     */
    public List<AgentTeam> getAllTeams() {
        return new ArrayList<>(teams.values());
    }

    /**
     * 获取团队数量
     * 
     * @return 团队数量
     */
    public int getTeamCount() {
        return teams.size();
    }

    /**
     * 清空所有团队
     */
    public void clearAll() {
        teams.clear();
        logger.info("All AgentTeams cleared");
    }

    /**
     * 获取管理器状态
     * 
     * @return 状态描述
     */
    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("AgentTeamManager Status:\n");
        sb.append("  Total teams: ").append(teams.size()).append("\n");
        
        for (AgentTeam team : teams.values()) {
            sb.append("  - ").append(team.getTeamName())
              .append(" (").append(team.getTeamId()).append("): ")
              .append(team.getSubAgentCount()).append(" sub-agents\n");
        }
        
        return sb.toString();
    }

    /**
     * 查找包含特定 Agent 的团队
     * 
     * @param agentCode Agent 代码
     * @return 包含该 Agent 的团队列表
     */
    public List<AgentTeam> findTeamsWithAgent(String agentCode) {
        List<AgentTeam> result = new ArrayList<>();
        for (AgentTeam team : teams.values()) {
            if (team.hasSubAgent(agentCode)) {
                result.add(team);
            }
        }
        return result;
    }
}
