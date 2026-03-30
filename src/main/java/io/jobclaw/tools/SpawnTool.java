package io.jobclaw.tools;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.AgentOrchestrator;
import io.jobclaw.agent.AgentRole;
import io.jobclaw.agent.ExecutionEvent;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.bus.OutboundMessage;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 子 Agent 生成工具
 *
 * 直接调用后端已实现的 AgentOrchestrator 多智能体协作系统
 *
 * 支持两种模式：
 * - 同步模式（默认）：等待子 Agent 完成并返回结果。主 Agent 通过工具返回值感知执行结果。
 * - 异步模式（async=true）：后台运行，完成后通知。
 *
 * SSE 流式输出：
 * - 主 Agent 的思考流会显示 "spawn 工具正在执行..."
 * - 子 Agent 执行过程不会推送到前端 SSE（前端只订阅主 session）
 * - 同步模式：工具返回值包含子 Agent 的完整结果
 * - 异步模式：完成后通过 ExecutionTraceService 推送 SSE 通知 + MessageBus 发送到外部通道
 *
 * 注意：使用 @Lazy 注入 AgentOrchestrator 避免循环依赖
 */
@Component
public class SpawnTool {

    private final AgentOrchestrator orchestrator;
    private final MessageBus messageBus;
    private final ExecutionTraceService executionTraceService;

    public SpawnTool(@Lazy AgentOrchestrator orchestrator,
                     MessageBus messageBus,
                     ExecutionTraceService executionTraceService) {
        this.orchestrator = orchestrator;
        this.messageBus = messageBus;
        this.executionTraceService = executionTraceService;
    }

    @Tool(name = "spawn", description = "Spawn a sub-agent to handle a task. This tool delegates tasks to the backend multi-agent collaboration system (AgentOrchestrator). Default: synchronous execution (wait for result). Set async=true for background execution (fire-and-forget). Use for delegating complex tasks, parallel processing, or specialized agent roles.")
    public String spawn(
        @ToolParam(description = "Task for the sub-agent to complete") String task,
        @ToolParam(description = "Optional label for the task (for display)") String label,
        @ToolParam(description = "Execute asynchronously. Default: false (synchronous). Set true for background execution (fire-and-forget).") Boolean async,
        @ToolParam(description = "Specify a role for the sub-agent (optional). Available roles: assistant, coder, researcher, writer, reviewer, planner, tester. If not specified, the orchestrator will auto-detect the appropriate mode.") String role
    ) {
        if (task == null || task.isEmpty()) {
            return "Error: task parameter is required";
        }

        // 从 AgentExecutionContext 获取父 sessionKey
        String parentSessionKey = AgentExecutionContext.getCurrentSessionKey();
        if (parentSessionKey == null) {
            parentSessionKey = "web:default";
        }

        boolean isAsync = async != null && async;
        String taskLabel = (label != null && !label.isEmpty()) ? label : "Unnamed Task";

        // Build session key for sub-agent
        String childSessionKey = "spawn-" + System.currentTimeMillis();

        if (isAsync) {
            // Async mode: spawn in background thread
            return spawnAsync(task, taskLabel, role, childSessionKey, parentSessionKey);
        } else {
            // Sync mode: wait for result
            return spawnSync(task, taskLabel, role, childSessionKey);
        }
    }

    /**
     * Synchronous spawn - wait for result
     * 结果直接返回给主 Agent（作为工具返回值）
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
     * 子 Agent 在后台执行，完成后：
     * 1. 通过 ExecutionTraceService 推送 SSE 通知到父 session（Web 前端）
     * 2. 通过 MessageBus 发送到外部通道（Telegram/Discord 等）
     */
    private String spawnAsync(String task, String label, String role, String childSessionKey, String parentSessionKey) {
        // Spawn in background thread
        Thread workerThread = new Thread(() -> {
            try {
                String result;
                if (role != null && !role.isEmpty()) {
                    AgentRole agentRole = AgentRole.fromCode(role.toLowerCase());
                    if (agentRole != null) {
                        result = orchestrator.processWithRole(childSessionKey, task, agentRole);
                    } else {
                        result = orchestrator.process(childSessionKey, task);
                    }
                } else {
                    result = orchestrator.process(childSessionKey, task);
                }

                String notificationContent = "✅ 异步任务完成: **" + label + "**\n\n" + result;

                // 1. 通过 ExecutionTraceService 推送 SSE 到 Web 前端
                ExecutionEvent notification = new ExecutionEvent(
                    parentSessionKey,
                    ExecutionEvent.EventType.CUSTOM,
                    notificationContent,
                    java.util.Map.of("asyncTaskLabel", label, "asyncTaskStatus", "completed")
                );
                executionTraceService.publish(notification);

                // 2. 通过 MessageBus 发送到外部通道
                OutboundMessage outbound = new OutboundMessage(
                    "web",
                    parentSessionKey,
                    notificationContent
                );
                messageBus.publishOutbound(outbound);

                System.out.println("[SpawnTool] Async task '" + label + "' completed");

            } catch (Exception e) {
                System.err.println("[SpawnTool] Async task '" + label + "' failed: " + e.getMessage());

                String errorContent = "❌ 异步任务失败: **" + label + "**\n\n错误: " + e.getMessage();

                // 1. SSE 通知
                ExecutionEvent notification = new ExecutionEvent(
                    parentSessionKey,
                    ExecutionEvent.EventType.ERROR,
                    errorContent,
                    java.util.Map.of("asyncTaskLabel", label, "asyncTaskStatus", "failed")
                );
                executionTraceService.publish(notification);

                // 2. MessageBus 通知
                OutboundMessage outbound = new OutboundMessage(
                    "web",
                    parentSessionKey,
                    errorContent
                );
                messageBus.publishOutbound(outbound);
            }
        }, "spawn-async-" + label);

        workerThread.setDaemon(true);
        workerThread.start();

        return "✅ Spawned background sub-agent for task: **" + label + "**\n\n" +
               "**Task**: " + task + "\n" +
               "**Mode**: Asynchronous (fire-and-forget)\n" +
               "**Status**: Running in background\n\n" +
               "The sub-agent will complete the task independently. " +
               "You will be notified via SSE when complete.";
    }
}