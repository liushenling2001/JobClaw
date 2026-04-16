package io.jobclaw.tools;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.AgentOrchestrator;
import io.jobclaw.agent.AgentRole;
import io.jobclaw.agent.ExecutionEvent;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.agent.TaskHarnessService;
import io.jobclaw.agent.catalog.AgentCatalogService;
import io.jobclaw.agent.runtime.AgentRunIds;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.config.Config;
import io.jobclaw.bus.OutboundMessage;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Spawn a child agent from the current agent execution.
 */
@Component
public class SpawnTool {

    private final AgentOrchestrator orchestrator;
    private final AgentCatalogService agentCatalogService;
    private final MessageBus messageBus;
    private final ExecutionTraceService executionTraceService;
    private final TaskHarnessService taskHarnessService;
    private final Config config;
    private final ExecutorService spawnExecutor = Executors.newCachedThreadPool();

    public SpawnTool(@Lazy AgentOrchestrator orchestrator,
                     AgentCatalogService agentCatalogService,
                     MessageBus messageBus,
                     ExecutionTraceService executionTraceService,
                     TaskHarnessService taskHarnessService,
                     Config config) {
        this.orchestrator = orchestrator;
        this.agentCatalogService = agentCatalogService;
        this.messageBus = messageBus;
        this.executionTraceService = executionTraceService;
        this.taskHarnessService = taskHarnessService;
        this.config = config;
    }

    @Tool(name = "spawn", description = "Spawn a sub-agent to handle a task. Use role for built-in roles. Use agent for persistent agents created through agent_catalog. Default is synchronous. Set async=true to run in the background and stream progress updates back to the parent session.")
    public String spawn(
            @ToolParam(description = "Task for the sub-agent to complete") String task,
            @ToolParam(description = "Optional label for the task") String label,
            @ToolParam(description = "Execute asynchronously. Default false.") Boolean async,
            @ToolParam(description = "Optional role for the sub-agent") String role,
            @ToolParam(description = "Optional persistent agent name or alias. If provided, spawn will resolve the saved agent definition and run it.") String agent,
            @ToolParam(description = "Optional subtask id to bind this child run to a tracked work item.") String subtaskId,
            @ToolParam(description = "Optional timeout in milliseconds for synchronous execution. Defaults to agent.subtaskTimeoutMs.") Long timeoutMs
    ) {
        if (task == null || task.isEmpty()) {
            return "Error: task parameter is required";
        }

        AgentExecutionContext.ExecutionScope parentScope = AgentExecutionContext.getCurrentScope();
        String parentSessionKey = parentScope != null && parentScope.sessionKey() != null
                ? parentScope.sessionKey()
                : "web:default";
        String parentRunId = parentScope != null ? parentScope.runId() : null;
        Consumer<ExecutionEvent> parentEventCallback = parentScope != null ? parentScope.eventCallback() : null;

        boolean isAsync = async != null && async;
        String taskLabel = label != null && !label.isEmpty() ? label : "Unnamed Task";
        String childSessionKey = "spawn-" + System.currentTimeMillis();
        String childRunId = AgentRunIds.newChildRunId();
        String effectiveSubtaskId = subtaskId != null && !subtaskId.isBlank() ? subtaskId.trim() : null;
        long effectiveTimeoutMs = timeoutMs != null ? timeoutMs : config.getAgent().getSubtaskTimeoutMs();

        if (parentRunId != null && effectiveSubtaskId != null) {
            taskHarnessService.startSubtask(
                    parentRunId,
                    effectiveSubtaskId,
                    taskLabel,
                    childSessionKey,
                    Map.of("source", "spawn", "mode", isAsync ? "async" : "sync"),
                    parentEventCallback
            );
        }

        if (isAsync) {
            return spawnAsync(task, taskLabel, role, agent, childSessionKey, childRunId,
                    parentSessionKey, parentRunId, effectiveSubtaskId, effectiveTimeoutMs, parentEventCallback);
        }
        return spawnSync(task, taskLabel, role, agent, childSessionKey, effectiveSubtaskId, effectiveTimeoutMs, parentRunId, parentEventCallback);
    }

    private String spawnSync(String task,
                             String label,
                             String role,
                             String agent,
                             String sessionKey,
                             String subtaskId,
                             long timeoutMs,
                             String parentRunId,
                             Consumer<ExecutionEvent> parentEventCallback) {
        try {
            if (agent != null && !agent.isBlank()) {
                var definition = agentCatalogService.resolveDefinition(agent.trim());
                if (definition.isEmpty()) {
                    completeTrackedSubtask(parentRunId, subtaskId, false,
                            "Agent not found: " + agent.trim(), parentEventCallback);
                    return "Error spawning sub-agent: agent not found - " + agent.trim();
                }
                String result = executeWithTimeout(
                        () -> orchestrator.processWithDefinition(sessionKey, task, definition.get()),
                        timeoutMs
                );
                completeTrackedSubtask(parentRunId, subtaskId, true, truncate(result), parentEventCallback);
                return "Spawned sub-agent: **" + definition.get().getDisplayName() + "**\n\n" +
                        "**Task**: " + label + "\n\n" +
                        "**Result**:\n" +
                        result;
            }
            if (role != null && !role.isEmpty()) {
                AgentRole agentRole = AgentRole.fromCode(role.toLowerCase());
                if (agentRole != null) {
                    String result = executeWithTimeout(
                            () -> orchestrator.processWithRole(sessionKey, task, agentRole),
                            timeoutMs
                    );
                    completeTrackedSubtask(parentRunId, subtaskId, true, truncate(result), parentEventCallback);
                    return "Spawned sub-agent with role: **" + agentRole.getDisplayName() + "**\n\n" +
                            "**Task**: " + label + "\n\n" +
                            "**Result**:\n" +
                            result;
                }
            }

            String enhancedTask = "[Sub-agent Task: " + label + "] " + task;
            String result = executeWithTimeout(() -> orchestrator.process(sessionKey, enhancedTask), timeoutMs);
            completeTrackedSubtask(parentRunId, subtaskId, true, truncate(result), parentEventCallback);

            return "Sub-agent completed task: **" + label + "**\n\n" +
                    "**Task**: " + task + "\n\n" +
                    "**Result**:\n" +
                    result;

        } catch (Exception e) {
            completeTrackedSubtask(parentRunId, subtaskId, false, e.getMessage(), parentEventCallback);
            return "Error spawning sub-agent: " + e.getMessage();
        }
    }

    private String spawnAsync(String task,
                              String label,
                              String role,
                              String agent,
                              String childSessionKey,
                              String childRunId,
                              String parentSessionKey,
                              String parentRunId,
                              String subtaskId,
                              long timeoutMs,
                              Consumer<ExecutionEvent> parentEventCallback) {
        var resolvedDefinition = agent != null && !agent.isBlank()
                ? agentCatalogService.resolveDefinition(agent.trim())
                : java.util.Optional.<io.jobclaw.agent.AgentDefinition>empty();
        String agentId = resolvedDefinition.map(io.jobclaw.agent.AgentDefinition::getCode)
                .orElse(role != null && !role.isEmpty() ? role.toLowerCase() : "assistant");
        String agentName = resolvedDefinition.map(io.jobclaw.agent.AgentDefinition::getDisplayName)
                .orElse(resolveAgentName(role));

        executionTraceService.publish(new ExecutionEvent(
                parentSessionKey,
                ExecutionEvent.EventType.CUSTOM,
                "Background task started: " + label,
                Map.of(
                        "asyncTaskLabel", label,
                        "asyncTaskStatus", "running",
                        "childSessionKey", childSessionKey
                ),
                childRunId,
                parentRunId,
                agentId,
                agentName
        ));

        Thread workerThread = new Thread(() -> {
            AgentExecutionContext.ExecutionScope childScope = new AgentExecutionContext.ExecutionScope(
                    childSessionKey,
                    event -> executionTraceService.publish(remapEvent(
                            event,
                            parentSessionKey,
                            childRunId,
                            parentRunId,
                            agentId,
                            agentName,
                            label
                    )),
                    childRunId,
                    parentRunId,
                    agentId,
                    agentName
            );
            AgentExecutionContext.setCurrentContext(childScope);
            try {
                String result;
                if (resolvedDefinition.isPresent()) {
                    result = orchestrator.processWithDefinition(childSessionKey, task, resolvedDefinition.get(),
                            event -> executionTraceService.publish(remapEvent(
                                    event, parentSessionKey, childRunId, parentRunId, agentId, agentName, label)));
                } else if (role != null && !role.isEmpty()) {
                    AgentRole agentRole = AgentRole.fromCode(role.toLowerCase());
                    if (agentRole != null) {
                        result = orchestrator.processWithRole(
                                childSessionKey,
                                task,
                                agentRole,
                                event -> executionTraceService.publish(remapEvent(
                                        event, parentSessionKey, childRunId, parentRunId, agentId, agentName, label))
                        );
                    } else {
                        result = orchestrator.process(childSessionKey, task,
                                event -> executionTraceService.publish(remapEvent(
                                        event, parentSessionKey, childRunId, parentRunId, agentId, agentName, label)));
                    }
                } else {
                    result = orchestrator.process(childSessionKey, task,
                            event -> executionTraceService.publish(remapEvent(
                                    event, parentSessionKey, childRunId, parentRunId, agentId, agentName, label)));
                }

                String notificationContent = "Async task completed: **" + label + "**\n\n" + result;
                ExecutionEvent notification = new ExecutionEvent(
                        parentSessionKey,
                        ExecutionEvent.EventType.CUSTOM,
                        notificationContent,
                        Map.of("asyncTaskLabel", label, "asyncTaskStatus", "completed"),
                        childRunId,
                        parentRunId,
                        agentId,
                        agentName
                );
                executionTraceService.publish(notification);

                OutboundMessage outbound = new OutboundMessage("web", parentSessionKey, notificationContent);
                messageBus.publishOutbound(outbound);
                completeTrackedSubtask(parentRunId, subtaskId, true, truncate(result), parentEventCallback);
            } catch (Exception e) {
                String errorContent = "Async task failed: **" + label + "**\n\nError: " + e.getMessage();
                ExecutionEvent notification = new ExecutionEvent(
                        parentSessionKey,
                        ExecutionEvent.EventType.ERROR,
                        errorContent,
                        Map.of("asyncTaskLabel", label, "asyncTaskStatus", "failed"),
                        childRunId,
                        parentRunId,
                        agentId,
                        agentName
                );
                executionTraceService.publish(notification);

                OutboundMessage outbound = new OutboundMessage("web", parentSessionKey, errorContent);
                messageBus.publishOutbound(outbound);
                completeTrackedSubtask(parentRunId, subtaskId, false, e.getMessage(), parentEventCallback);
            } finally {
                AgentExecutionContext.clear();
            }
        }, "spawn-async-" + label);

        workerThread.setDaemon(true);
        workerThread.start();

        return "Spawned background sub-agent for task: **" + label + "**\n\n" +
                "**Task**: " + task + "\n" +
                "**Mode**: Asynchronous\n" +
                "**Run ID**: " + childRunId + "\n" +
                "**Status**: Running in background";
    }

    private ExecutionEvent remapEvent(ExecutionEvent childEvent,
                                      String parentSessionKey,
                                      String childRunId,
                                      String parentRunId,
                                      String agentId,
                                      String agentName,
                                      String label) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (childEvent.getMetadata() != null) {
            metadata.putAll(childEvent.getMetadata());
        }
        metadata.put("asyncTaskLabel", label);
        metadata.put("childSessionId", childEvent.getSessionId());

        return new ExecutionEvent(
                parentSessionKey,
                childEvent.getType(),
                childEvent.getContent(),
                metadata,
                childRunId,
                parentRunId,
                agentId,
                agentName
        );
    }

    private String resolveAgentName(String role) {
        if (role == null || role.isBlank()) {
            return "Assistant";
        }
        AgentRole agentRole = AgentRole.fromCode(role.toLowerCase());
        return agentRole != null ? agentRole.getDisplayName() : role;
    }

    private String executeWithTimeout(Callable<String> action, long timeoutMs) throws Exception {
        Future<String> future = spawnExecutor.submit(action);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException("Sub-agent timed out after " + timeoutMs + " ms", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new RuntimeException(cause != null ? cause : e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sub-agent execution interrupted", e);
        }
    }

    private void completeTrackedSubtask(String parentRunId,
                                        String subtaskId,
                                        boolean success,
                                        String summary,
                                        Consumer<ExecutionEvent> parentEventCallback) {
        if (parentRunId == null || subtaskId == null || subtaskId.isBlank()) {
            return;
        }
        taskHarnessService.completeSubtask(
                parentRunId,
                subtaskId,
                summary,
                success,
                Map.of("source", "spawn"),
                parentEventCallback
        );
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500) + "\n[truncated]";
    }
}
