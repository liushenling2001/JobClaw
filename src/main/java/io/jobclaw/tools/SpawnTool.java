package io.jobclaw.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 子 Agent 生成工具
 * 
 * 支持同步和异步两种模式：
 * - 同步模式：等待子 Agent 完成并返回结果
 * - 异步模式：后台运行，完成后通知
 */
@Component
public class SpawnTool {

    @Tool(name = "spawn", description = "Spawn a sub-agent to handle a task. Default: synchronous execution (wait for result and return it). Set async=true for background execution (returns immediately, notifies when done). Use for delegating complex tasks, parallel processing, or specialized agent roles.")
    public String spawn(
        @ToolParam(description = "Task for the sub-agent to complete") String task,
        @ToolParam(description = "Optional label for the task (for display)") String label,
        @ToolParam(description = "Execute asynchronously. Default: false (synchronous, wait for result). Set true for background execution.") Boolean async
    ) {
        if (task == null || task.isEmpty()) {
            return "Error: task parameter is required";
        }

        boolean isAsync = async != null && async;
        String taskLabel = (label != null && !label.isEmpty()) ? label : "Unnamed Task";

        // TODO: Integrate with actual AgentOrchestrator/SessionManager
        // For now, return placeholder response
        if (isAsync) {
            return "Spawned background agent for task: '" + taskLabel + "'\n\n" +
                   "Task: " + task + "\n" +
                   "Mode: Asynchronous (fire-and-forget)\n" +
                   "Status: Running in background\n\n" +
                   "Note: Agent will notify when complete. (Pending full integration)";
        } else {
            return "Sub-agent executed task: '" + taskLabel + "'\n\n" +
                   "Task: " + task + "\n" +
                   "Mode: Synchronous (wait for result)\n" +
                   "Result: [Placeholder - awaiting AgentOrchestrator integration]\n\n" +
                   "Note: This is a placeholder response. Full integration pending.";
        }
    }
}
