package io.jobclaw.agent;

import io.jobclaw.session.SessionManager;
import io.jobclaw.tools.FileTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 编排器
 * 
 * 负责协调多个 Agent 协作完成复杂任务
 * 支持单 Agent 模式和多 Agent 协作模式
 */
@Component
public class AgentOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final AgentRegistry agentRegistry;
    private final SessionManager sessionManager;
    private final FileTools fileTools;
    private final AgentTeamManager teamManager;

    // 用于识别多 Agent 请求的模式
    private static final Pattern MULTI_AGENT_PATTERN = Pattern.compile(
        "(多智能体 | 多 agent|multi.agent|协作 | 协同 | 团队 | team|collaborat)",
        Pattern.CASE_INSENSITIVE
    );

    // 用于识别团队模式请求的模式
    private static final Pattern TEAM_MODE_PATTERN = Pattern.compile(
        "(创建团队 | 组建团队 |create.team|setup.team|agent.team)",
        Pattern.CASE_INSENSITIVE
    );

    // 用于识别指定角色的模式
    private static final Pattern ROLE_PATTERN = Pattern.compile(
        "(作为 | 扮演 | 用|use|as|role)[:：\\s]*(程序员 | 研究员 | 作家 | 审查员 | 规划师 | 测试员|coder|researcher|writer|reviewer|planner|tester)",
        Pattern.CASE_INSENSITIVE
    );

    public AgentOrchestrator(AgentRegistry agentRegistry, SessionManager sessionManager, 
                             FileTools fileTools, AgentTeamManager teamManager) {
        this.agentRegistry = agentRegistry;
        this.sessionManager = sessionManager;
        this.fileTools = fileTools;
        this.teamManager = teamManager;
        
        logger.info("AgentOrchestrator initialized with AgentTeamManager support");
    }

    /**
     * 公开方法：使用指定角色处理请求
     * 供 SpawnTool 调用
     * 
     * @param sessionKey 会话密钥
     * @param userContent 用户输入
     * @param role 指定的 Agent 角色
     * @return Agent 响应
     */
    public String processWithRole(String sessionKey, String userContent, AgentRole role) {
        logger.info("Orchestrator processing with role {} for session {}", role.getDisplayName(), sessionKey);
        return handleSingleAgentWithRole(sessionKey, userContent, role);
    }

    /**
     * 处理用户请求
     * 
     * @param sessionKey 会话密钥
     * @param userContent 用户输入
     * @return Agent 响应
     */
    public String process(String sessionKey, String userContent) {
        logger.info("Orchestrator processing request for session {}", sessionKey);

        // 🔒 递归防护：子 Agent 不再自动触发多 Agent 协作
        // 如果 sessionKey 包含 "spawn-" 或 "subagent-" 前缀，说明是子 Agent 调用
        // 强制使用单 Agent 模式，避免无限递归
        boolean isSubAgent = sessionKey.startsWith("spawn-") || sessionKey.startsWith("subagent-");
        
        if (isSubAgent) {
            logger.debug("Sub-agent detected, forcing single-agent mode to prevent recursion");
            // 子 Agent 默认使用 ASSISTANT 角色
            return handleSingleAgentDefault(sessionKey, userContent);
        }

        // 1. 判断是否需要创建团队
        if (isTeamModeRequest(userContent)) {
            logger.info("Team mode detected");
            return handleTeamMode(sessionKey, userContent);
        }

        // 2. 判断是否需要多 Agent 协作
        if (isMultiAgentRequest(userContent)) {
            logger.info("Multi-agent mode detected");
            return handleMultiAgent(sessionKey, userContent);
        }

        // 3. 判断是否指定了角色
        AgentRole specifiedRole = extractSpecifiedRole(userContent);
        if (specifiedRole != null) {
            logger.info("Specified role detected: {}", specifiedRole.getDisplayName());
            return handleSingleAgentWithRole(sessionKey, userContent, specifiedRole);
        }

        // 4. 默认单 Agent 模式
        logger.info("Single-agent mode (default)");
        return handleSingleAgentDefault(sessionKey, userContent);
    }

    /**
     * 判断是否为团队模式请求
     */
    private boolean isTeamModeRequest(String userContent) {
        if (userContent == null || userContent.isEmpty()) {
            return false;
        }
        
        Matcher matcher = TEAM_MODE_PATTERN.matcher(userContent);
        return matcher.find();
    }

    /**
     * 判断是否为多 Agent 请求
     */
    private boolean isMultiAgentRequest(String userContent) {
        if (userContent == null || userContent.isEmpty()) {
            return false;
        }
        
        Matcher matcher = MULTI_AGENT_PATTERN.matcher(userContent);
        return matcher.find();
    }

    /**
     * 提取用户指定的角色
     */
    private AgentRole extractSpecifiedRole(String userContent) {
        if (userContent == null || userContent.isEmpty()) {
            return null;
        }
        
        Matcher matcher = ROLE_PATTERN.matcher(userContent);
        if (matcher.find()) {
            String roleCode = matcher.group(2).toLowerCase();
            return AgentRole.fromCode(roleCode);
        }
        
        return null;
    }

    /**
     * 处理单 Agent 请求（默认模式）
     */
    private String handleSingleAgentDefault(String sessionKey, String userContent) {
        try {
            AgentLoop agent = agentRegistry.getOrCreateAgent(AgentRole.ASSISTANT, sessionKey);
            return agent.process(sessionKey, userContent);
        } catch (Exception e) {
            logger.error("Error in single-agent mode", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 处理单 Agent 请求（指定角色）
     */
    private String handleSingleAgentWithRole(String sessionKey, String userContent, AgentRole role) {
        try {
            AgentLoop agent = agentRegistry.getOrCreateAgent(role, sessionKey);
            return agent.process(sessionKey, userContent, role);
        } catch (Exception e) {
            logger.error("Error in single-agent mode with role", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 处理团队模式请求
     * 
     * 流程：
     * 1. 创建 Agent 团队
     * 2. 主智能体分析任务
     * 3. 动态创建需要的子智能体
     * 4. 分配任务给子智能体
     * 5. 汇总结果
     */
    private String handleTeamMode(String sessionKey, String userContent) {
        try {
            StringBuilder result = new StringBuilder();
            result.append("## 🤖 Agent 团队模式\n\n");

            // Step 1: 创建团队
            logger.info("Step 1: Creating agent team");
            AgentDefinition masterDef = AgentDefinition.fromRole(AgentRole.PLANNER);
            AgentTeam team = teamManager.createTeam("临时团队", masterDef, sessionKey);
            result.append("### 1️⃣ 团队创建\n\n");
            result.append("**团队名称**: ").append(team.getTeamName()).append("\n");
            result.append("**团队 ID**: ").append(team.getTeamId()).append("\n");
            result.append("**主智能体**: ").append(team.getMasterAgent().getDisplayName()).append("\n\n");

            // Step 2: 主智能体分析任务并决定需要的子智能体
            logger.info("Step 2: Master agent analyzing task");
            result.append("### 2️⃣ 任务分析\n\n");

            AgentLoop masterAgent = agentRegistry.getOrCreateAgent(team.getMasterAgent(), sessionKey);
            String analysisContent = userContent + "\n\n请分析这个任务，并列出需要哪些专业角色的智能体来协作完成。";
            String analysis = masterAgent.processWithDefinition(sessionKey, analysisContent, team.getMasterAgent());
            result.append(analysis).append("\n\n");

            // Step 3: 动态创建子智能体
            logger.info("Step 3: Creating sub-agents dynamically");
            result.append("### 3️⃣ 子智能体创建\n\n");

            // 根据分析结果创建子智能体（这里简化处理，创建常用角色）
            List<AgentDefinition> subAgents = List.of(
                AgentDefinition.fromRole(AgentRole.CODER),
                AgentDefinition.fromRole(AgentRole.RESEARCHER),
                AgentDefinition.fromRole(AgentRole.WRITER)
            );

            for (AgentDefinition subAgent : subAgents) {
                team.addSubAgent(subAgent);
                result.append("✅ 创建 **").append(subAgent.getDisplayName())
                      .append("** (`").append(subAgent.getCode()).append("`)\n");
            }
            result.append("\n");

            // Step 4: 分配任务给子智能体
            logger.info("Step 4: Assigning tasks to sub-agents");
            result.append("### 4️⃣ 任务执行\n\n");

            for (AgentDefinition subAgent : subAgents) {
                AgentLoop agent = agentRegistry.getOrCreateAgent(subAgent, sessionKey);
                String taskContent = userContent + "\n\n请从你的专业角度完成这个任务。";
                String subResult = agent.processWithDefinition(sessionKey, taskContent, subAgent);
                
                result.append("**").append(subAgent.getDisplayName()).append("**:\n")
                      .append(subResult).append("\n\n");
                
                // 添加到共享记忆
                team.addToSharedMemory(subAgent.getDisplayName() + ": " + subResult.substring(0, Math.min(200, subResult.length())));
            }

            // Step 5: 汇总结果
            logger.info("Step 5: Summarizing results");
            result.append("### 5️⃣ 最终总结\n\n");

            AgentLoop summarizer = agentRegistry.getOrCreateAgent(AgentRole.WRITER, sessionKey);
            String summaryContent = "请综合以下所有子智能体的工作成果，提供一个清晰、完整的最终答案：\n\n" + 
                team.getSharedMemory().stream().map(m -> "- " + m).reduce((a, b) -> a + "\n" + b).orElse("");
            String summary = summarizer.processWithDefinition(sessionKey, summaryContent, 
                AgentDefinition.fromRole(AgentRole.WRITER));
            result.append(summary);

            // 保存团队信息到共享记忆
            team.addToSharedMemory("最终总结：" + summary.substring(0, Math.min(200, summary.length())));

            logger.info("Team mode completed: {} ({} sub-agents)", 
                team.getTeamName(), team.getSubAgentCount());
            return result.toString();

        } catch (Exception e) {
            logger.error("Error in team mode", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 处理多 Agent 协作请求
     * 
     * 流程：
     * 1. Planner 分析任务并分解
     * 2. 根据任务类型分配给相应角色的 Agent
     * 3. 各 Agent 独立完成任务
     * 4. Reviewer 审查结果
     * 5. Writer 汇总输出
     */
    private String handleMultiAgent(String sessionKey, String userContent) {
        try {
            StringBuilder result = new StringBuilder();
            result.append("## 🤖 多智能体协作模式\n\n");

            // Step 1: Planner 分析任务
            logger.info("Step 1: Planner analyzing task");
            result.append("### 1️⃣ 任务分析\n\n");
            
            AgentLoop planner = agentRegistry.getOrCreateAgent(AgentRole.PLANNER, sessionKey);
            String planContent = userContent + "\n\n请分析这个任务，并分解为具体的执行步骤。";
            String plan = planner.process(sessionKey, planContent, AgentRole.PLANNER);
            result.append(plan).append("\n\n");

            // Step 2: 根据任务内容分配执行 Agent
            logger.info("Step 2: Assigning execution agents");
            result.append("### 2️⃣ 任务执行\n\n");

            List<AgentRole> executionRoles = determineExecutionRoles(userContent);
            List<String> executionResults = new ArrayList<>();

            for (AgentRole role : executionRoles) {
                logger.info("Executing with role: {}", role.getDisplayName());
                AgentLoop executor = agentRegistry.getOrCreateAgent(role, sessionKey);
                String execContent = userContent + "\n\n请根据上述任务分析，完成你的部分。";
                String execResult = executor.process(sessionKey, execContent, role);
                executionResults.add("**" + role.getDisplayName() + "**:\n" + execResult);
            }

            result.append(String.join("\n\n", executionResults)).append("\n\n");

            // Step 3: Reviewer 审查
            logger.info("Step 3: Reviewer checking results");
            result.append("### 3️⃣ 质量审查\n\n");

            AgentLoop reviewer = agentRegistry.getOrCreateAgent(AgentRole.REVIEWER, sessionKey);
            String reviewContent = "请审查以下工作成果，检查质量、完整性和潜在问题：\n\n" + 
                String.join("\n\n", executionResults);
            String review = reviewer.process(sessionKey, reviewContent, AgentRole.REVIEWER);
            result.append(review).append("\n\n");

            // Step 4: Writer 汇总
            logger.info("Step 4: Writer summarizing");
            result.append("### 4️⃣ 最终总结\n\n");

            AgentLoop writer = agentRegistry.getOrCreateAgent(AgentRole.WRITER, sessionKey);
            String summaryContent = "请综合以下所有内容，提供一个清晰、完整的最终答案：\n\n" + 
                String.join("\n\n", executionResults) + "\n\n审查意见:\n" + review;
            String summary = writer.process(sessionKey, summaryContent, AgentRole.WRITER);
            result.append(summary);

            logger.info("Multi-agent collaboration completed");
            return result.toString();

        } catch (Exception e) {
            logger.error("Error in multi-agent mode", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 根据任务内容确定需要的执行角色
     */
    private List<AgentRole> determineExecutionRoles(String userContent) {
        List<AgentRole> roles = new ArrayList<>();
        String content = userContent.toLowerCase();

        // 代码相关
        if (content.contains("代码") || content.contains("编程") || content.contains("开发") ||
            content.contains("code") || content.contains("program") || content.contains("develop")) {
            roles.add(AgentRole.CODER);
        }

        // 研究分析相关
        if (content.contains("研究") || content.contains("分析") || content.contains("调查") ||
            content.contains("research") || content.contains("analyze") || content.contains("investigate")) {
            roles.add(AgentRole.RESEARCHER);
        }

        // 文档写作相关
        if (content.contains("文档") || content.contains("写作") || content.contains("报告") ||
            content.contains("document") || content.contains("write") || content.contains("report")) {
            roles.add(AgentRole.WRITER);
        }

        // 测试相关
        if (content.contains("测试") || content.contains("验证") || content.contains("检查") ||
            content.contains("test") || content.contains("verify") || content.contains("check")) {
            roles.add(AgentRole.TESTER);
        }

        // 如果没有匹配到特定角色，默认使用程序员和研究员
        if (roles.isEmpty()) {
            roles.addAll(Arrays.asList(AgentRole.CODER, AgentRole.RESEARCHER));
        }

        return roles;
    }

    /**
     * 获取编排器状态
     */
    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("AgentOrchestrator Status:\n");
        sb.append("  Mode: Auto (single/multi agent)\n");
        sb.append("  ").append(agentRegistry.getPoolStatus().replace("\n", "\n  "));
        return sb.toString();
    }
}
