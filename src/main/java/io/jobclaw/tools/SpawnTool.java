package io.jobclaw.tools;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.AgentDefinition;
import io.jobclaw.agent.AgentOrchestrator;
import io.jobclaw.agent.ExecutionEvent;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.agent.TaskHarnessService;
import io.jobclaw.agent.profile.AgentProfileService;
import io.jobclaw.agent.profile.ResolvedAgentRuntime;
import io.jobclaw.agent.runtime.AgentRunIds;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.config.Config;
import io.jobclaw.bus.OutboundMessage;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Spawn a child agent from the current agent execution.
 */
@Component
public class SpawnTool {

    private final AgentOrchestrator orchestrator;
    private final AgentProfileService agentProfileService;
    private final MessageBus messageBus;
    private final ExecutionTraceService executionTraceService;
    private final TaskHarnessService taskHarnessService;
    private final Config config;
    private final ExecutorService spawnExecutor = Executors.newCachedThreadPool();

    public SpawnTool(@Lazy AgentOrchestrator orchestrator,
                     AgentProfileService agentProfileService,
                     MessageBus messageBus,
                     ExecutionTraceService executionTraceService,
                     TaskHarnessService taskHarnessService,
                     Config config) {
        this.orchestrator = orchestrator;
        this.agentProfileService = agentProfileService;
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
        AgentDefinition parentDefinition = parentScope != null ? parentScope.definition() : null;

        boolean isAsync = async != null && async;
        String taskLabel = label != null && !label.isEmpty() ? label : "Unnamed Task";
        String childSessionKey = "spawn-" + System.currentTimeMillis();
        String childRunId = AgentRunIds.newChildRunId();
        String effectiveSubtaskId = subtaskId != null && !subtaskId.isBlank() ? subtaskId.trim() : null;
        ResolvedAgentRuntime resolvedRuntime = agentProfileService.resolveRuntime(role, agent).orElse(null);

        if ((agent != null && !agent.isBlank()) || (role != null && !role.isBlank())) {
            if (resolvedRuntime == null) {
                return agent != null && !agent.isBlank()
                        ? "Error spawning sub-agent: agent not found - " + agent.trim()
                        : "Error spawning sub-agent: unknown role - " + role.trim();
            }
        }

        AgentDefinition effectiveDefinition = mergeDefinition(parentDefinition,
                resolvedRuntime != null ? resolvedRuntime.definition() : null);
        long effectiveTimeoutMs = resolveTimeout(timeoutMs, effectiveDefinition);

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
            return spawnAsync(task, taskLabel, effectiveDefinition, childSessionKey, childRunId,
                    parentSessionKey, parentRunId, effectiveSubtaskId, effectiveTimeoutMs, parentEventCallback);
        }
        return spawnSync(task, taskLabel, effectiveDefinition, childSessionKey, effectiveSubtaskId, effectiveTimeoutMs, parentRunId, parentEventCallback);
    }

    private String spawnSync(String task,
                             String label,
                             AgentDefinition definition,
                             String sessionKey,
                             String subtaskId,
                             long timeoutMs,
                             String parentRunId,
                             Consumer<ExecutionEvent> parentEventCallback) {
        try {
            if (definition != null) {
                String result = executeWithTimeout(
                        () -> orchestrator.processWithDefinition(sessionKey, task, definition),
                        timeoutMs
                );
                completeTrackedSubtask(parentRunId, subtaskId, true, truncate(result), parentEventCallback);
                return "Spawned sub-agent: **" + definition.getDisplayName() + "**\n\n" +
                        "**Task**: " + label + "\n\n" +
                        "**Result**:\n" +
                        result;
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
                              AgentDefinition definition,
                              String childSessionKey,
                              String childRunId,
                              String parentSessionKey,
                              String parentRunId,
                              String subtaskId,
                              long timeoutMs,
                              Consumer<ExecutionEvent> parentEventCallback) {
        String agentId = definition != null ? definition.getCode() : "assistant";
        String agentName = definition != null ? definition.getDisplayName() : "Assistant";

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
                    agentName,
                    definition
            );
            AgentExecutionContext.setCurrentContext(childScope);
            try {
                String result;
                if (definition != null) {
                    result = orchestrator.processWithDefinition(childSessionKey, task, definition,
                            event -> executionTraceService.publish(remapEvent(
                                    event, parentSessionKey, childRunId, parentRunId, agentId, agentName, label)));
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

    private long resolveTimeout(Long explicitTimeoutMs, AgentDefinition effectiveDefinition) {
        if (explicitTimeoutMs != null && explicitTimeoutMs > 0) {
            return explicitTimeoutMs;
        }
        if (effectiveDefinition != null
                && effectiveDefinition.getConfig() != null
                && effectiveDefinition.getConfig().getTimeoutMs() != null
                && effectiveDefinition.getConfig().getTimeoutMs() > 0) {
            return effectiveDefinition.getConfig().getTimeoutMs();
        }
        return config.getAgent().getSubtaskTimeoutMs();
    }

    private AgentDefinition mergeDefinition(AgentDefinition parentDefinition, AgentDefinition overlayDefinition) {
        if (parentDefinition == null) {
            return overlayDefinition;
        }
        if (overlayDefinition == null) {
            return cloneDefinition(parentDefinition);
        }

        AgentDefinition.Builder builder = AgentDefinition.builder()
                .code(overlayDefinition.getCode() != null && !overlayDefinition.getCode().isBlank()
                        ? overlayDefinition.getCode()
                        : parentDefinition.getCode())
                .displayName(overlayDefinition.getDisplayName() != null && !overlayDefinition.getDisplayName().isBlank()
                        ? overlayDefinition.getDisplayName()
                        : parentDefinition.getDisplayName())
                .systemPrompt(overlayDefinition.getSystemPrompt() != null && !overlayDefinition.getSystemPrompt().isBlank()
                        ? overlayDefinition.getSystemPrompt()
                        : parentDefinition.getSystemPrompt())
                .description(overlayDefinition.getDescription() != null && !overlayDefinition.getDescription().isBlank()
                        ? overlayDefinition.getDescription()
                        : parentDefinition.getDescription())
                .allowedTools(firstNonEmpty(overlayDefinition.getAllowedTools(), parentDefinition.getAllowedTools()))
                .allowedSkills(firstNonEmpty(overlayDefinition.getAllowedSkills(), parentDefinition.getAllowedSkills()))
                .config(mergeConfig(parentDefinition.getConfig(), overlayDefinition.getConfig()));

        return builder.build();
    }

    private AgentDefinition cloneDefinition(AgentDefinition definition) {
        if (definition == null) {
            return null;
        }
        return AgentDefinition.builder()
                .code(definition.getCode())
                .displayName(definition.getDisplayName())
                .systemPrompt(definition.getSystemPrompt())
                .description(definition.getDescription())
                .allowedTools(definition.getAllowedTools() != null ? List.copyOf(definition.getAllowedTools()) : null)
                .allowedSkills(definition.getAllowedSkills() != null ? List.copyOf(definition.getAllowedSkills()) : null)
                .config(mergeConfig(null, definition.getConfig()))
                .build();
    }

    private AgentDefinition.AgentConfig mergeConfig(AgentDefinition.AgentConfig parentConfig,
                                                    AgentDefinition.AgentConfig overlayConfig) {
        if (parentConfig == null && overlayConfig == null) {
            return null;
        }

        AgentDefinition.AgentConfig merged = new AgentDefinition.AgentConfig();
        AgentDefinition.AgentConfig base = parentConfig != null ? parentConfig : new AgentDefinition.AgentConfig();
        AgentDefinition.AgentConfig overlay = overlayConfig != null ? overlayConfig : new AgentDefinition.AgentConfig();

        merged.setProvider(firstNonBlank(overlay.getProvider(), base.getProvider()));
        merged.setModel(firstNonBlank(overlay.getModel(), base.getModel()));
        merged.setTemperature(overlay.getTemperature() != null ? overlay.getTemperature() : base.getTemperature());
        merged.setMaxTokens(overlay.getMaxTokens() != null ? overlay.getMaxTokens() : base.getMaxTokens());
        merged.setTimeoutMs(overlay.getTimeoutMs() != null ? overlay.getTimeoutMs() : base.getTimeoutMs());
        merged.setApiBase(firstNonBlank(overlay.getApiBase(), base.getApiBase()));

        if (base.getCustomSettings() != null) {
            base.getCustomSettings().forEach(merged::setCustomSetting);
        }
        if (overlay.getCustomSettings() != null) {
            overlay.getCustomSettings().forEach(merged::setCustomSetting);
        }
        return merged;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private <T> List<T> firstNonEmpty(List<T> preferred, List<T> fallback) {
        if (preferred != null && !preferred.isEmpty()) {
            return List.copyOf(preferred);
        }
        return fallback != null && !fallback.isEmpty() ? List.copyOf(fallback) : null;
    }
}
