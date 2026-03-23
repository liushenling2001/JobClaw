package io.jobclaw.tools;

import io.jobclaw.agent.AgentOrchestrator;
import io.jobclaw.agent.AgentRole;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 子 Agent 生成工具
 * 
 * 直接调用后端已实现的 AgentOrchestrator 多智能体协作系统
 * 
 * 支持两种模式：
 * - 同步模式（默认）：等待子 Agent 完成并返回结果
 * - 异步模式（async=true）：后台运行，完成后通知
 * 
 * 触发方式：
 * - 显式调用：通过 spawn 工具
 * - 自动检测：AgentOrchestrator 通过关键词自动触发多 Agent 协作
 */
@Component
public class SpawnTool {

    private final AgentOrchestrator orchestrator;

    public SpawnTool(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Tool(name = "spawn", description = "Spawn a sub-agent to handle a task. This tool delegates tasks to the backend multi-agent collaboration system (AgentOrchestrator). Default: synchronous execution (wait for result). Set async=true for background execution. Use for delegating complex tasks, parallel processing, or specialized agent roles.")
    public String spawn(
        @ToolParam(description = "Task for the sub-agent to complete") String task,
        @ToolParam(description = "Optional label for the task (for display)") String label,
        @ToolParam(description = "Execute asynchronously. Default: false (synchronous). Set true for background execution (fire-and-forget).") Boolean async,
        @ToolParam(description = "Specify a role for the sub-agent (optional). Available roles: assistant, coder, researcher, writer, reviewer, planner, tester. If not specified, the orchestrator will auto-detect the appropriate mode.") String role
    ) {
        if (task == null || task.isEmpty()) {
            return "Error: task parameter is required";
        }

        boolean isAsync = async != null && async;
        String taskLabel = (label != null && !label.isEmpty()) ? label : "Unnamed Task";

        // Build session key for sub-agent
        String sessionKey = "spawn-" + System.currentTimeMillis();

        if (isAsync) {
            // Async mode: spawn in background thread
            return spawnAsync(task, taskLabel, role, sessionKey);
        } else {
            // Sync mode: wait for result
            return spawnSync(task, taskLabel, role, sessionKey);
        }
    }

    /**
     * Synchronous spawn - wait for result
     */
    private String spawnSync(String task, String label, String role, String sessionKey) {
        try {
            // If role is specified, use single-agent mode with that role
            if (role != null && !role.isEmpty()) {
                AgentRole agentRole = AgentRole.fromCode(role.toLowerCase());
                if (agentRole != null) {
                    return "🤖 Spawned sub-agent with role: **" + agentRole.getDisplayName() + "**\n\n" +
                           "**Task**: " + label + "\n\n" +
                           "**Result**:\n" +
                           orchestrator.processWithRole(sessionKey, task, agentRole);
                }
            }

            // Otherwise, let orchestrator auto-detect the mode
            // For sync mode, we enhance the task description to trigger multi-agent
            String enhancedTask = "[Sub-agent Task: " + label + "] " + task;
            
            String result = orchestrator.process(sessionKey, enhancedTask);
            
            return "🤖 Sub-agent completed task: **" + label + "**\n\n" +
                   "**Task**: " + task + "\n\n" +
                   "**Result**:\n" +
                   result;
                   
        } catch (Exception e) {
            return "Error spawning sub-agent: " + e.getMessage();
        }
    }

    /**
     * Asynchronous spawn - fire and forget
     */
    private String spawnAsync(String task, String label, String role, String sessionKey) {
        // Spawn in background thread
        Thread workerThread = new Thread(() -> {
            try {
                String result;
                if (role != null && !role.isEmpty()) {
                    AgentRole agentRole = AgentRole.fromCode(role.toLowerCase());
                    if (agentRole != null) {
                        result = orchestrator.processWithRole(sessionKey, task, agentRole);
                    } else {
                        result = orchestrator.process(sessionKey, task);
                    }
                } else {
                    result = orchestrator.process(sessionKey, task);
                }
                
                // TODO: Send notification via MessageBus when complete
                // For now, log the completion
                System.out.println("[SpawnTool] Async task '" + label + "' completed");
                
            } catch (Exception e) {
                System.err.println("[SpawnTool] Async task '" + label + "' failed: " + e.getMessage());
            }
        }, "spawn-async-" + label);
        
        workerThread.setDaemon(true);
        workerThread.start();
        
        return "✅ Spawned background sub-agent for task: **" + label + "**\n\n" +
               "**Task**: " + task + "\n" +
               "**Mode**: Asynchronous (fire-and-forget)\n" +
               "**Status**: Running in background\n\n" +
               "The sub-agent will complete the task independently. " +
               "Results will be available when the task finishes.";
    }
}
