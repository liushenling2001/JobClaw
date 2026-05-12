package io.jobclaw.tools;

import io.jobclaw.agent.AgentDefinition;
import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.AgentOrchestrator;
import io.jobclaw.agent.ExecutionEvent;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.agent.profile.AgentProfileService;
import io.jobclaw.agent.profile.ResolvedAgentRuntime;
import io.jobclaw.config.Config;
import io.jobclaw.context.result.ContextRef;
import io.jobclaw.context.result.NoopResultStore;
import io.jobclaw.context.result.ResultStore;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Spawn a child agent without binding it to any implicit task ledger.
 */
@Component
public class SpawnTool {
    private static final int DEFAULT_CHILD_AGENT_RESULT_MAX_CHARS = 4000;

    private final AgentOrchestrator orchestrator;
    private final AgentProfileService agentProfileService;
    private final ExecutionTraceService executionTraceService;
    private final Config config;
    private final ResultStore resultStore;
    private final ExecutorService spawnExecutor = Executors.newCachedThreadPool();

    public SpawnTool(@Lazy AgentOrchestrator orchestrator,
                     AgentProfileService agentProfileService,
                     ExecutionTraceService executionTraceService,
                     Config config,
                     ResultStore resultStore) {
        this.orchestrator = orchestrator;
        this.agentProfileService = agentProfileService;
        this.executionTraceService = executionTraceService;
        this.config = config;
        this.resultStore = resultStore != null ? resultStore : new NoopResultStore();
    }

    @Tool(name = "spawn", description = "Spawn a child agent to handle a bounded task. Use role for built-in roles or agent for persistent agents from agent_catalog.")
    public String spawn(
            @ToolParam(description = "Task for the child agent to complete") String task,
            @ToolParam(description = "Optional label for the task") String label,
            @ToolParam(description = "Execute asynchronously. Default false.") Boolean async,
            @ToolParam(description = "Optional role for the child agent") String role,
            @ToolParam(description = "Optional persistent agent name or alias from agent_catalog") String agent,
            @ToolParam(description = "Optional timeout in milliseconds for synchronous execution") Long timeoutMs
    ) {
        if (task == null || task.isBlank()) {
            return "Error: task parameter is required";
        }

        ResolvedAgentRuntime runtime = resolveRuntime(role, agent);
        if (runtime == null) {
            return agent != null && !agent.isBlank()
                    ? "Error spawning child agent: agent not found - " + agent.trim()
                    : "Error spawning child agent: unknown role - " + role.trim();
        }

        String taskLabel = label != null && !label.isBlank() ? label : "Child Agent Task";
        String childSessionKey = "spawn-" + System.currentTimeMillis();
        AgentDefinition definition = runtime.definition();
        AgentExecutionContext.ExecutionScope parentScope = AgentExecutionContext.getCurrentScope();
        Consumer<ExecutionEvent> parentCallback = parentScope != null ? parentScope.eventCallback() : null;
        boolean isAsync = async != null && async;
        long effectiveTimeout = timeoutMs != null && timeoutMs > 0
                ? timeoutMs
                : config.getAgent().getChildAgentTimeoutMs();

        if (isAsync) {
            spawnExecutor.submit(() -> runChild(task, childSessionKey, definition, parentCallback));
            return "Child agent started asynchronously: " + taskLabel + " (session=" + childSessionKey + ")";
        }

        Future<String> future = spawnExecutor.submit(() -> runChild(task, childSessionKey, definition, parentCallback));
        try {
            String result = future.get(effectiveTimeout, TimeUnit.MILLISECONDS);
            return parentHandoff(result, taskLabel);
        } catch (TimeoutException e) {
            future.cancel(true);
            return "Error spawning child agent: timed out after " + effectiveTimeout + " ms";
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return "Error spawning child agent: interrupted";
        } catch (Exception e) {
            return "Error spawning child agent: " + e.getMessage();
        }
    }

    private ResolvedAgentRuntime resolveRuntime(String role, String agent) {
        Optional<ResolvedAgentRuntime> runtime = agentProfileService.resolveRuntime(role, agent);
        return runtime.orElse(null);
    }

    private String runChild(String task,
                            String childSessionKey,
                            AgentDefinition definition,
                            Consumer<ExecutionEvent> parentCallback) {
        Consumer<ExecutionEvent> callback = event -> {
            executionTraceService.publish(event);
            if (parentCallback != null) {
                parentCallback.accept(event);
            }
        };
        if (definition != null) {
            return orchestrator.processWithDefinition(childSessionKey, task, definition, callback);
        }
        return orchestrator.process(childSessionKey, task, callback);
    }

    private String parentHandoff(String value, String label) {
        if (value == null) {
            return "";
        }
        int maxChars = config != null && config.getAgent() != null && config.getAgent().getChildAgentResultMaxChars() > 0
                ? config.getAgent().getChildAgentResultMaxChars()
                : DEFAULT_CHILD_AGENT_RESULT_MAX_CHARS;
        if (value.length() <= maxChars) {
            return value;
        }

        AgentExecutionContext.ExecutionScope scope = AgentExecutionContext.getCurrentScope();
        ContextRef ref = resultStore.save(
                scope != null ? scope.sessionKey() : "unknown-session",
                scope != null ? scope.runId() : null,
                "subagent",
                label,
                value
        );
        return """
                Child agent result stored as a context reference.

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
}
