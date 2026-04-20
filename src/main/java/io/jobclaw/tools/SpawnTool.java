package io.jobclaw.tools;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.AgentDefinition;
import io.jobclaw.agent.AgentOrchestrator;
import io.jobclaw.agent.ExecutionEvent;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.agent.TaskHarnessService;
import io.jobclaw.agent.TaskHarnessRun;
import io.jobclaw.agent.TaskHarnessSubtask;
import io.jobclaw.agent.profile.AgentProfileService;
import io.jobclaw.agent.profile.ResolvedAgentRuntime;
import io.jobclaw.agent.runtime.AgentRunIds;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.config.Config;
import io.jobclaw.context.result.ContextRef;
import io.jobclaw.context.result.NoopResultStore;
import io.jobclaw.context.result.ResultStore;
import io.jobclaw.bus.OutboundMessage;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final String ERROR_PREFIX = "Error:";
    private static final int DEFAULT_SUBTASK_RESULT_MAX_CHARS = 4000;

    private final AgentOrchestrator orchestrator;
    private final AgentProfileService agentProfileService;
    private final MessageBus messageBus;
    private final ExecutionTraceService executionTraceService;
    private final TaskHarnessService taskHarnessService;
    private final Config config;
    private final ResultStore resultStore;
    private final ExecutorService spawnExecutor = Executors.newCachedThreadPool();

    @Autowired
    public SpawnTool(@Lazy AgentOrchestrator orchestrator,
                     AgentProfileService agentProfileService,
                     MessageBus messageBus,
                     ExecutionTraceService executionTraceService,
                     TaskHarnessService taskHarnessService,
                     Config config,
                     ResultStore resultStore) {
        this.orchestrator = orchestrator;
        this.agentProfileService = agentProfileService;
        this.messageBus = messageBus;
        this.executionTraceService = executionTraceService;
        this.taskHarnessService = taskHarnessService;
        this.config = config;
        this.resultStore = resultStore != null ? resultStore : new NoopResultStore();
    }

    public SpawnTool(@Lazy AgentOrchestrator orchestrator,
                     AgentProfileService agentProfileService,
                     MessageBus messageBus,
                     ExecutionTraceService executionTraceService,
                     TaskHarnessService taskHarnessService,
                     Config config) {
        this(orchestrator, agentProfileService, messageBus, executionTraceService, taskHarnessService, config, new NoopResultStore());
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
        return spawnSync(task, taskLabel, effectiveDefinition, childSessionKey, childRunId, effectiveSubtaskId,
                effectiveTimeoutMs, parentSessionKey, parentRunId, parentEventCallback);
    }

    private String spawnSync(String task,
                             String label,
                             AgentDefinition definition,
                             String sessionKey,
                             String childRunId,
                             String subtaskId,
                             long timeoutMs,
                             String parentSessionKey,
                             String parentRunId,
                             Consumer<ExecutionEvent> parentEventCallback) {
        String agentId = definition != null ? definition.getCode() : "assistant";
        String agentName = definition != null ? definition.getDisplayName() : "Assistant";
        Consumer<ExecutionEvent> childEventCallback = event -> publishRemappedEvent(
                event, parentSessionKey, childRunId, parentRunId, agentId, agentName, label);
        try {
            String isolatedTask = buildIsolatedSubtaskInput(task);
            if (definition != null) {
                String result = executeWithTimeout(
                        () -> orchestrator.processWithDefinition(sessionKey, isolatedTask, definition, childEventCallback),
                        timeoutMs
                );
                if (isFailureResult(result)) {
                    completeTrackedSubtask(parentRunId, subtaskId, false, subtaskSummary(result),
                            failureMetadata(result), parentEventCallback);
                    return failedResult(label, result, sessionKey);
                }
                completeTrackedSubtask(parentRunId, subtaskId, true, subtaskSummary(result),
                        Map.of("source", "spawn"), parentEventCallback);
                return "Spawned sub-agent: **" + definition.getDisplayName() + "**\n\n" +
                        "**Task**: " + label + "\n\n" +
                        "**Child session**: " + sessionKey + "\n\n" +
                        "**Result**:\n" +
                        parentHandoff(result, parentSessionKey, parentRunId, label);
            }

            String enhancedTask = "[Sub-agent Task: " + label + "] " + isolatedTask;
            String result = executeWithTimeout(() -> orchestrator.process(sessionKey, enhancedTask, childEventCallback), timeoutMs);
            if (isFailureResult(result)) {
                completeTrackedSubtask(parentRunId, subtaskId, false, subtaskSummary(result),
                        failureMetadata(result), parentEventCallback);
                return failedResult(label, result, sessionKey);
            }
            completeTrackedSubtask(parentRunId, subtaskId, true, subtaskSummary(result),
                    Map.of("source", "spawn"), parentEventCallback);

            return "Sub-agent completed task: **" + label + "**\n\n" +
                    "**Task**: " + task + "\n\n" +
                    "**Child session**: " + sessionKey + "\n\n" +
                    "**Result**:\n" +
                    parentHandoff(result, parentSessionKey, parentRunId, label);

        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            completeTrackedSubtask(parentRunId, subtaskId, false, message,
                    failureMetadata(message), parentEventCallback);
            publishSyncFailure(parentSessionKey, parentRunId, subtaskId, label, message, parentEventCallback);
            return "Sub-agent failed task: **" + label + "**\n\nError: " + message;
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
                String isolatedTask = buildIsolatedSubtaskInput(task);
                String result;
                if (definition != null) {
                    result = orchestrator.processWithDefinition(childSessionKey, isolatedTask, definition,
                            event -> executionTraceService.publish(remapEvent(
                                    event, parentSessionKey, childRunId, parentRunId, agentId, agentName, label)));
                } else {
                    result = orchestrator.process(childSessionKey, isolatedTask,
                            event -> executionTraceService.publish(remapEvent(
                                    event, parentSessionKey, childRunId, parentRunId, agentId, agentName, label)));
                }

                String notificationContent = "Async task completed: **" + label + "**\n\n"
                        + "**Child session**: " + childSessionKey + "\n\n"
                        + parentHandoff(result, parentSessionKey, parentRunId, label);
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
                if (isFailureResult(result)) {
                    completeTrackedSubtask(parentRunId, subtaskId, false, subtaskSummary(result),
                            failureMetadata(result), parentEventCallback);
                } else {
                    completeTrackedSubtask(parentRunId, subtaskId, true, subtaskSummary(result),
                            Map.of("source", "spawn", "mode", "async"), parentEventCallback);
                }
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
                completeTrackedSubtask(parentRunId, subtaskId, false, e.getMessage(),
                        failureMetadata(e.getMessage()), parentEventCallback);
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
        metadata.put("spawnTaskLabel", label);
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

    private void publishRemappedEvent(ExecutionEvent childEvent,
                                      String parentSessionKey,
                                      String childRunId,
                                      String parentRunId,
                                      String agentId,
                                      String agentName,
                                      String label) {
        executionTraceService.publish(remapEvent(
                childEvent,
                parentSessionKey,
                childRunId,
                parentRunId,
                agentId,
                agentName,
                label
        ));
    }

    private String executeWithTimeout(Callable<String> action, long timeoutMs) throws Exception {
        AgentExecutionContext.ExecutionScope capturedScope = AgentExecutionContext.getCurrentScope();
        Future<String> future = spawnExecutor.submit(() -> {
            AgentExecutionContext.ExecutionScope previousScope = AgentExecutionContext.getCurrentScope();
            if (capturedScope != null) {
                AgentExecutionContext.setCurrentContext(capturedScope);
            }
            try {
                return action.call();
            } finally {
                if (previousScope != null) {
                    AgentExecutionContext.setCurrentContext(previousScope);
                } else {
                    AgentExecutionContext.clear();
                }
            }
        });
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

    private boolean isFailureResult(String result) {
        return result != null && result.stripLeading().startsWith(ERROR_PREFIX);
    }

    private String failedResult(String label, String result, String childSessionKey) {
        return "Sub-agent failed task: **" + label + "**\n\n"
                + "**Child session**: " + childSessionKey + "\n\n"
                + parentHandoff(result, null, null, label);
    }

    private Map<String, Object> failureMetadata(String message) {
        String failureType = classifyFailureType(message);
        return Map.of(
                "source", "spawn",
                "failureType", failureType,
                "retryable", isRetryableFailureType(failureType)
        );
    }

    private String classifyFailureType(String message) {
        String normalized = message == null ? "" : message.toLowerCase();
        if (normalized.contains("timed out") || normalized.contains("timeout")) {
            return "timeout";
        }
        if (normalized.contains("interrupted")) {
            return "interrupted";
        }
        if (normalized.contains("network") || normalized.contains("api key")
                || normalized.contains("bad request") || normalized.contains("llm")) {
            return "transient_model_error";
        }
        if (normalized.stripLeading().startsWith("error:")) {
            return "child_error";
        }
        return "unknown";
    }

    private boolean isRetryableFailureType(String failureType) {
        return "timeout".equals(failureType)
                || "interrupted".equals(failureType)
                || "transient_model_error".equals(failureType)
                || "child_error".equals(failureType);
    }

    private void publishSyncFailure(String parentSessionKey,
                                    String parentRunId,
                                    String subtaskId,
                                    String label,
                                    String message,
                                    Consumer<ExecutionEvent> parentEventCallback) {
        if (parentEventCallback == null) {
            return;
        }
        parentEventCallback.accept(new ExecutionEvent(
                parentSessionKey,
                ExecutionEvent.EventType.ERROR,
                "Sub-agent failed: " + label + " - " + message,
                Map.of(
                        "source", "spawn",
                        "subtaskId", subtaskId != null ? subtaskId : "",
                        "parentRunId", parentRunId != null ? parentRunId : "",
                        "spawnStatus", "failed"
                ),
                parentRunId,
                null,
                "spawn",
                "Spawn Tool"
        ));
    }

    private void completeTrackedSubtask(String parentRunId,
                                        String subtaskId,
                                        boolean success,
                                        String summary,
                                        Map<String, Object> metadata,
                                        Consumer<ExecutionEvent> parentEventCallback) {
        if (parentRunId == null || subtaskId == null || subtaskId.isBlank()) {
            return;
        }
        Map<String, Object> effectiveMetadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
        effectiveMetadata.putIfAbsent("source", "spawn");
        if (!success) {
            effectiveMetadata.put("retryCount", nextRetryCount(parentRunId, subtaskId));
        }
        taskHarnessService.completeSubtask(
                parentRunId,
                subtaskId,
                summary,
                success,
                effectiveMetadata,
                parentEventCallback
        );
    }

    private int nextRetryCount(String parentRunId, String subtaskId) {
        TaskHarnessRun run = taskHarnessService.getRun(parentRunId);
        if (run == null) {
            return 1;
        }
        return run.getSubtasks().stream()
                .filter(subtask -> subtaskId.equals(subtask.id()))
                .findFirst()
                .map(TaskHarnessSubtask::metadata)
                .map(metadata -> metadata.get("retryCount"))
                .map(this::intValue)
                .map(value -> value + 1)
                .orElse(1);
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String buildIsolatedSubtaskInput(String task) {
        return """
                [Subtask Isolation Policy]
                You are executing one isolated subtask. Do not rely on parent chat history.
                Use tools to inspect only the specific input needed for this subtask.
                Return only a concise result for this subtask: status, key findings, issue list, and next action if needed.
                Do not include large source excerpts, full document text, or unrelated worklist items.

                Subtask:
                """ + task;
    }

    private String subtaskSummary(String value) {
        return boundedText(value, 1200);
    }

    private String parentHandoff(String value) {
        return parentHandoff(value, null, null, null);
    }

    private String parentHandoff(String value, String parentSessionKey, String parentRunId, String label) {
        int maxChars = config != null && config.getAgent() != null && config.getAgent().getSubtaskResultMaxChars() > 0
                ? config.getAgent().getSubtaskResultMaxChars()
                : DEFAULT_SUBTASK_RESULT_MAX_CHARS;
        if (shouldStoreSubtaskResult(value, maxChars)) {
            ContextRef ref = resultStore.save(
                    parentSessionKey,
                    parentRunId,
                    "subtask",
                    label != null && !label.isBlank() ? label : "spawn",
                    value
            );
            return """
                    Subtask result stored as a context reference.

                    refId: %s
                    source: %s
                    contentLength: %d

                    preview:
                    %s

                    Use context_ref(action='read', refId='%s', start='0', maxChars='12000') or context_ref(action='search', refId='%s', query='...') if more detail is needed.
                    """.formatted(
                    ref.getRefId(),
                    ref.getSourceName(),
                    ref.getContentLength(),
                    ref.getPreview() != null ? ref.getPreview() : "",
                    ref.getRefId(),
                    ref.getRefId()
            );
        }
        return boundedText(value, maxChars);
    }

    private boolean shouldStoreSubtaskResult(String value, int maxChars) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (config == null || config.getAgent() == null || !config.getAgent().isContextRefEnabled()) {
            return false;
        }
        int threshold = Math.max(1, config.getAgent().getContextRefThresholdChars());
        return value.length() > Math.max(threshold, maxChars);
    }

    private String boundedText(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars)
                + "\n\n[Subtask result truncated for parent context; full output is stored in the child session.]";
    }

    private long resolveTimeout(Long explicitTimeoutMs, AgentDefinition effectiveDefinition) {
        long configuredTimeout = config.getAgent().getSubtaskTimeoutMs();
        if (effectiveDefinition != null
                && effectiveDefinition.getConfig() != null
                && effectiveDefinition.getConfig().getTimeoutMs() != null
                && effectiveDefinition.getConfig().getTimeoutMs() > 0) {
            configuredTimeout = effectiveDefinition.getConfig().getTimeoutMs();
        }
        Long profileSubtaskTimeoutMs = profileSubtaskTimeoutMs(effectiveDefinition);
        if (profileSubtaskTimeoutMs != null && profileSubtaskTimeoutMs > 0) {
            configuredTimeout = profileSubtaskTimeoutMs;
        }
        if (explicitTimeoutMs != null && explicitTimeoutMs > 0) {
            return Math.max(explicitTimeoutMs, configuredTimeout);
        }
        return configuredTimeout;
    }

    private Long profileSubtaskTimeoutMs(AgentDefinition effectiveDefinition) {
        if (effectiveDefinition == null || effectiveDefinition.getConfig() == null) {
            return null;
        }
        Object value = effectiveDefinition.getConfig().getCustomSetting("subtaskTimeoutMs");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Long.parseLong(stringValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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
