package io.jobclaw.tools;

import io.jobclaw.agent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * 多 Agent 协作工具
 *
 * 支持多 Agent 并行协作完成复杂任务。复用现有的 AgentOrchestrator 和 AgentRegistry。
 *
 * 协作模式：
 * - TEAM: 团队协作模式，多个 Agent 并行执行任务，然后汇总结果
 * - SEQUENTIAL: 顺序执行模式，Agent 按顺序依次完成任务
 * - DEBATE: 辩论模式，两个 Agent 分别从正反两面讨论问题
 *
 * 角色定义：
 * - 可使用预置角色（coder, researcher, writer, reviewer, planner, tester）
 * - 可自定义角色（提供 name 和 prompt）
 * - 每个角色可指定允许使用的工具白名单
 */
@Component
public class CollaborateTool {

    private static final Logger logger = LoggerFactory.getLogger(CollaborateTool.class);

    private final AgentOrchestrator orchestrator;
    private final AgentRegistry agentRegistry;
    private final AgentTeamManager teamManager;

    public CollaborateTool(@Lazy AgentOrchestrator orchestrator,
                          @Lazy AgentRegistry agentRegistry,
                          AgentTeamManager teamManager) {
        this.orchestrator = orchestrator;
        this.agentRegistry = agentRegistry;
        this.teamManager = teamManager;
    }

    @Tool(name = "collaborate", description = "Launch multi-agent collaboration. Supports modes: TEAM (parallel execution), SEQUENTIAL (step-by-step), DEBATE (pros/cons discussion). Each agent can be a predefined role (coder, researcher, writer, reviewer, planner, tester) or custom role with name/prompt. Optionally specify allowed_tools whitelist per agent.")
    public String collaborate(
        @ToolParam(description = "Collaboration mode: TEAM (parallel), SEQUENTIAL (step-by-step), DEBATE (pros/cons)") String mode,
        @ToolParam(description = "Task or topic for collaboration") String task,
        @ToolParam(description = "Agent roles (JSON array). Each role: {name: string, prompt?: string, allowed_tools?: string[]}. If prompt omitted, uses predefined role by name.") String roles,
        @ToolParam(description = "Maximum rounds/iterations (default: 1 for TEAM, 3 for DEBATE)") Integer maxRounds,
        @ToolParam(description = "Timeout in milliseconds (default: 60000)") Long timeoutMs
    ) {
        if (mode == null || mode.isEmpty()) {
            return "Error: mode is required (TEAM, SEQUENTIAL, or DEBATE)";
        }
        if (task == null || task.isEmpty()) {
            return "Error: task is required";
        }

        try {
            CollaborationMode collabMode = parseMode(mode);
            List<AgentDefinition> agentDefs = parseRoles(roles);
            int rounds = maxRounds != null ? maxRounds : (collabMode == CollaborationMode.DEBATE ? 3 : 1);
            long timeout = timeoutMs != null ? timeoutMs : 60000L;

            // 使用独立的协作 sessionKey，避免与主 session 混淆
            String collabSessionKey = "collab-" + System.currentTimeMillis();

            logger.info("Starting collaboration: mode={}, agents={}, sessionKey={}",
                mode, agentDefs.size(), collabSessionKey);

            return switch (collabMode) {
                case TEAM -> executeTeamMode(collabSessionKey, task, agentDefs, timeout);
                case SEQUENTIAL -> executeSequentialMode(collabSessionKey, task, agentDefs, timeout);
                case DEBATE -> executeDebateMode(collabSessionKey, task, agentDefs, rounds, timeout);
            };

        } catch (Exception e) {
            logger.error("Collaboration failed", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * TEAM 模式 - 并行执行，汇总结果
     */
    private String executeTeamMode(String collabSessionKey, String task,
                                   List<AgentDefinition> agents, long timeout) {
        StringBuilder result = new StringBuilder();
        result.append("## 🤝 团队协作模式\n\n");
        result.append("**任务**: ").append(task).append("\n\n");

        // 创建团队
        AgentDefinition masterDef = agents.isEmpty()
            ? AgentDefinition.fromRole(AgentRole.PLANNER)
            : agents.get(0);
        AgentTeam team = teamManager.createTeam("协作团队", masterDef, collabSessionKey);

        // 添加成员
        for (AgentDefinition def : agents) {
            team.addSubAgent(def);
        }

        result.append("### 📋 团队成员\n\n");
        for (AgentDefinition def : agents) {
            result.append("- **").append(def.getDisplayName()).append("**");
            if (def.getDescription() != null) {
                result.append(": ").append(def.getDescription());
            }
            result.append("\n");
        }
        result.append("\n");

        // 并行执行 - 每个 Agent 使用独立的 sessionKey 避免并发冲突
        result.append("### ⚡ 并行执行\n\n");
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(2, agents.size()));
        List<Future<String>> futures = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        for (AgentDefinition def : agents) {
            // 每个 Agent 使用独立的 sessionKey（避免并发写入同一个 Session）
            String agentSessionKey = collabSessionKey + "-" + def.getCode();
            futures.add(executor.submit(() -> {
                try {
                    AgentLoop agent = agentRegistry.getOrCreateAgent(def, agentSessionKey);
                    String agentTask = task + "\n\n请从你的专业角度完成这个任务。";
                    return agent.processWithDefinition(agentSessionKey, agentTask, def);
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }));
        }

        // 收集结果
        List<String> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                String agentResult = futures.get(i).get(timeout, TimeUnit.MILLISECONDS);
                results.add(agentResult);
                result.append("**").append(agents.get(i).getDisplayName()).append("**:\n")
                      .append(agentResult).append("\n\n");

                // 添加到共享记忆
                team.addToSharedMemory(agents.get(i).getDisplayName() + ": " +
                    agentResult.substring(0, Math.min(200, agentResult.length())));
            } catch (TimeoutException e) {
                result.append("**").append(agents.get(i).getDisplayName()).append("**: ⏱️ 超时\n\n");
            } catch (Exception e) {
                result.append("**").append(agents.get(i).getDisplayName()).append("**: ❌ ")
                      .append(e.getMessage()).append("\n\n");
            }
        }

        executor.shutdown();

        long elapsed = System.currentTimeMillis() - startTime;
        result.append("### 📊 执行统计\n\n");
        result.append("- 执行时间: ").append(elapsed).append("ms\n");
        result.append("- 参与 Agent: ").append(agents.size()).append("\n\n");

        // 汇总结果
        result.append("### 🎯 综合总结\n\n");
        AgentDefinition summarizerDef = AgentDefinition.fromRole(AgentRole.WRITER);
        String summarizerSessionKey = collabSessionKey + "-summarizer";
        AgentLoop summarizer = agentRegistry.getOrCreateAgent(summarizerDef, summarizerSessionKey);
        String summaryTask = "请综合以下各 Agent 的工作成果，提供清晰完整的总结：\n\n" +
            String.join("\n\n---\n\n", results);
        String summary = summarizer.processWithDefinition(summarizerSessionKey, summaryTask, summarizerDef);
        result.append(summary);

        // 清理团队
        teamManager.destroyTeam(team.getTeamId());

        return result.toString();
    }

    /**
     * SEQUENTIAL 模式 - 顺序执行
     */
    private String executeSequentialMode(String collabSessionKey, String task,
                                         List<AgentDefinition> agents, long timeout) {
        StringBuilder result = new StringBuilder();
        result.append("## 🔄 顺序执行模式\n\n");
        result.append("**任务**: ").append(task).append("\n\n");

        if (agents.isEmpty()) {
            agents = List.of(
                AgentDefinition.fromRole(AgentRole.PLANNER),
                AgentDefinition.fromRole(AgentRole.CODER),
                AgentDefinition.fromRole(AgentRole.REVIEWER)
            );
        }

        result.append("### 📋 执行流程\n\n");
        String currentContext = task;

        for (int i = 0; i < agents.size(); i++) {
            AgentDefinition def = agents.get(i);
            result.append("**Step ").append(i + 1).append(": ").append(def.getDisplayName()).append("**\n\n");

            try {
                // 每个 Agent 使用独立的 sessionKey
                String agentSessionKey = collabSessionKey + "-" + def.getCode() + "-" + i;
                AgentLoop agent = agentRegistry.getOrCreateAgent(def, agentSessionKey);
                String agentTask = currentContext + "\n\n请完成你的任务部分。";
                String agentResult = agent.processWithDefinition(agentSessionKey, agentTask, def);

                result.append(agentResult).append("\n\n");

                // 下一个 Agent 接收当前结果
                currentContext = "前序工作成果:\n" + agentResult + "\n\n继续任务: " + task;

            } catch (Exception e) {
                result.append("❌ Error: ").append(e.getMessage()).append("\n\n");
                break;
            }
        }

        return result.toString();
    }

    /**
     * DEBATE 模式 - 辩论讨论
     */
    private String executeDebateMode(String collabSessionKey, String topic,
                                     List<AgentDefinition> agents, int rounds, long timeout) {
        StringBuilder result = new StringBuilder();
        result.append("## 🗣️ 辩论模式\n\n");
        result.append("**辩题**: ").append(topic).append("\n\n");

        // 默认正反方
        if (agents.size() < 2) {
            agents = List.of(
                AgentDefinition.builder()
                    .code("proponent")
                    .displayName("正方")
                    .systemPrompt("你是辩论的正方，支持该观点。请提供有力的论据和证据支持你的立场。")
                    .build(),
                AgentDefinition.builder()
                    .code("opponent")
                    .displayName("反方")
                    .systemPrompt("你是辩论的反方，反对该观点。请提供有力的论据和证据反驳对方观点。")
                    .build()
            );
        }

        AgentDefinition proponent = agents.get(0);
        AgentDefinition opponent = agents.get(1);

        result.append("### ⚔️ 辩论双方\n\n");
        result.append("- **正方**: ").append(proponent.getDisplayName()).append("\n");
        result.append("- **反方**: ").append(opponent.getDisplayName()).append("\n\n");

        String proponentArg = "";
        String opponentArg = "";

        // 正反方使用独立的 sessionKey
        String proponentSessionKey = collabSessionKey + "-proponent";
        String opponentSessionKey = collabSessionKey + "-opponent";

        for (int round = 1; round <= rounds; round++) {
            result.append("### 🔄 第 ").append(round).append(" 轮\n\n");

            // 正方发言
            try {
                AgentLoop proAgent = agentRegistry.getOrCreateAgent(proponent, proponentSessionKey);
                String proTask = topic + "\n\n请阐述你的观点。";
                if (!opponentArg.isEmpty()) {
                    proTask = "反方观点:\n" + opponentArg + "\n\n请反驳并加强你的论点: " + topic;
                }
                proponentArg = proAgent.processWithDefinition(proponentSessionKey, proTask, proponent);
                result.append("**正方**:\n").append(proponentArg).append("\n\n");
            } catch (Exception e) {
                result.append("**正方**: ❌ ").append(e.getMessage()).append("\n\n");
            }

            // 反方发言
            try {
                AgentLoop oppAgent = agentRegistry.getOrCreateAgent(opponent, opponentSessionKey);
                String oppTask = "正方观点:\n" + proponentArg + "\n\n请反驳: " + topic;
                opponentArg = oppAgent.processWithDefinition(opponentSessionKey, oppTask, opponent);
                result.append("**反方**:\n").append(opponentArg).append("\n\n");
            } catch (Exception e) {
                result.append("**反方**: ❌ ").append(e.getMessage()).append("\n\n");
            }
        }

        // 总结
        result.append("### 📝 辩论总结\n\n");
        AgentDefinition summarizerDef = AgentDefinition.fromRole(AgentRole.WRITER);
        String summarizerSessionKey = collabSessionKey + "-summarizer";
        AgentLoop summarizer = agentRegistry.getOrCreateAgent(summarizerDef, summarizerSessionKey);
        String summaryTask = "请综合正反双方的辩论观点，提供一个客观的总结:\n\n正方观点:\n" +
            proponentArg + "\n\n反方观点:\n" + opponentArg;
        String summary = summarizer.processWithDefinition(summarizerSessionKey, summaryTask, summarizerDef);
        result.append(summary);

        return result.toString();
    }

    /**
     * 解析协作模式
     */
    private CollaborationMode parseMode(String mode) {
        return switch (mode.toUpperCase()) {
            case "TEAM" -> CollaborationMode.TEAM;
            case "SEQUENTIAL" -> CollaborationMode.SEQUENTIAL;
            case "DEBATE" -> CollaborationMode.DEBATE;
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        };
    }

    /**
     * 解析角色定义
     */
    private List<AgentDefinition> parseRoles(String rolesJson) {
        if (rolesJson == null || rolesJson.isEmpty()) {
            // 默认团队
            return List.of(
                AgentDefinition.fromRole(AgentRole.CODER),
                AgentDefinition.fromRole(AgentRole.RESEARCHER),
                AgentDefinition.fromRole(AgentRole.WRITER)
            );
        }

        List<AgentDefinition> defs = new ArrayList<>();

        try {
            // 简单 JSON 解析（不依赖 Jackson）
            // 格式: [{"name":"xxx","prompt":"xxx","allowed_tools":["tool1","tool2"]}]
            String json = rolesJson.trim();
            if (!json.startsWith("[") || !json.endsWith("]")) {
                logger.warn("Invalid roles JSON format, using defaults");
                return List.of(
                    AgentDefinition.fromRole(AgentRole.CODER),
                    AgentDefinition.fromRole(AgentRole.RESEARCHER)
                );
            }

            // 分割对象
            json = json.substring(1, json.length() - 1); // 移除 [ ]
            String[] objects = json.split("\\},\\s*\\{");

            for (String obj : objects) {
                obj = obj.replace("{", "").replace("}", "").trim();
                AgentDefinition def = parseRoleObject(obj);
                if (def != null) {
                    defs.add(def);
                }
            }

        } catch (Exception e) {
            logger.warn("Failed to parse roles JSON: {}", e.getMessage());
            return List.of(
                AgentDefinition.fromRole(AgentRole.CODER),
                AgentDefinition.fromRole(AgentRole.RESEARCHER)
            );
        }

        return defs.isEmpty()
            ? List.of(AgentDefinition.fromRole(AgentRole.ASSISTANT))
            : defs;
    }

    /**
     * 解析单个角色对象
     */
    private AgentDefinition parseRoleObject(String obj) {
        String name = extractJsonValue(obj, "name");
        String prompt = extractJsonValue(obj, "prompt");
        String allowedToolsStr = extractJsonValue(obj, "allowed_tools");

        if (name == null || name.isEmpty()) {
            return null;
        }

        // 如果没有 prompt，尝试使用预置角色
        if (prompt == null || prompt.isEmpty()) {
            AgentRole predefinedRole = AgentRole.fromCode(name.toLowerCase());
            if (predefinedRole != AgentRole.ASSISTANT || name.equalsIgnoreCase("assistant")) {
                return AgentDefinition.fromRole(predefinedRole);
            }
            // 自定义角色但无 prompt
            prompt = "你是一个 " + name + "，请完成分配给你的任务。";
        }

        // 解析工具白名单
        List<String> allowedTools = null;
        if (allowedToolsStr != null && !allowedToolsStr.isEmpty()) {
            allowedTools = parseJsonArray(allowedToolsStr);
        }

        return AgentDefinition.builder()
            .code(name.toLowerCase().replace(" ", "_"))
            .displayName(name)
            .systemPrompt(prompt)
            .allowedTools(allowedTools)
            .build();
    }

    /**
     * 提取 JSON 字段值
     */
    private String extractJsonValue(String obj, String key) {
        int keyIndex = obj.indexOf("\"" + key + "\"");
        if (keyIndex == -1) {
            keyIndex = obj.indexOf(key + ":");
            if (keyIndex == -1) return null;
        } else {
            keyIndex = obj.indexOf(":", keyIndex);
        }

        if (keyIndex == -1) return null;

        int valueStart = keyIndex + 1;
        while (valueStart < obj.length() && (obj.charAt(valueStart) == ' ' || obj.charAt(valueStart) == '"')) {
            valueStart++;
        }

        // 找到值结束位置
        char endChar = obj.charAt(valueStart - 1) == '"' ? '"' : ',';
        int valueEnd = obj.indexOf(endChar, valueStart);
        if (valueEnd == -1) {
            valueEnd = obj.length();
        }

        return obj.substring(valueStart, valueEnd).replace("\"", "").trim();
    }

    /**
     * 解析 JSON 数组
     */
    private List<String> parseJsonArray(String arrayStr) {
        List<String> result = new ArrayList<>();
        if (arrayStr == null || !arrayStr.startsWith("[") || !arrayStr.endsWith("]")) {
            return result;
        }

        String content = arrayStr.substring(1, arrayStr.length() - 1);
        String[] items = content.split(",");
        for (String item : items) {
            String cleaned = item.replace("\"", "").trim();
            if (!cleaned.isEmpty()) {
                result.add(cleaned);
            }
        }

        return result;
    }

    /**
     * 协作模式枚举
     */
    private enum CollaborationMode {
        TEAM,
        SEQUENTIAL,
        DEBATE
    }
}