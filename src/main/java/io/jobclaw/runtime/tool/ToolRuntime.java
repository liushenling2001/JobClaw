package io.jobclaw.runtime.tool;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.completion.ActiveExecutionRegistry;
import io.jobclaw.config.Config;
import io.jobclaw.context.result.ContextRef;
import io.jobclaw.context.result.NoopResultStore;
import io.jobclaw.context.result.ResultStore;
import io.jobclaw.providers.Message;
import io.jobclaw.providers.ToolCall;
import io.jobclaw.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ToolRuntime {

    private static final Logger logger = LoggerFactory.getLogger(ToolRuntime.class);
    private static final long MIN_TIMEOUT_MILLIS = 1_000L;
    private static final long SPAWN_TIMEOUT_GRACE_MILLIS = 30_000L;
    private static final long COMMAND_TIMEOUT_GRACE_MILLIS = 10_000L;
    private static final long MAX_REQUESTED_TOOL_TIMEOUT_MILLIS = TimeUnit.HOURS.toMillis(6);
    private static final long TOOL_PROGRESS_INTERVAL_MILLIS = 15_000L;
    private static final Pattern TIMEOUT_MS_PATTERN = Pattern.compile("\"?timeoutMs\"?\\s*[:=]\\s*(\\d+)");
    private static final Pattern TIMEOUT_SECONDS_PATTERN = Pattern.compile("\"?timeout(?:Seconds)?\"?\\s*[:=]\\s*(\\d+)");
    private static final int DUPLICATE_WARNING_COUNT = 3;
    private static final int DUPLICATE_BLOCK_COUNT = 4;
    private static final Set<String> SAFE_REPEAT_GUARD_TOOLS = Set.of(
            "read_file",
            "list_dir",
            "search_files",
            "grep",
            "rg",
            "find",
            "context_ref",
            "memory",
            "skills"
    );
    private static final ScheduledExecutorService TOOL_PROGRESS_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "jobclaw-tool-progress");
        thread.setDaemon(true);
        return thread;
    });

    private final Config config;
    private final SessionManager sessionManager;
    private final ExecutorService toolExecutionExecutor;
    private final ToolExecutionStateTracker stateTracker;
    private final ToolEventPublisher eventPublisher;
    private final ActiveExecutionRegistry activeExecutionRegistry;
    private final ResultStore resultStore;
    private final ConcurrentHashMap<String, TurnBudgetState> turnBudgetStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DuplicateGuardState> duplicateGuardStates = new ConcurrentHashMap<>();

    public ToolRuntime(Config config,
                       SessionManager sessionManager,
                       ExecutorService toolExecutionExecutor,
                       ToolExecutionStateTracker stateTracker) {
        this(config, sessionManager, toolExecutionExecutor, stateTracker, new ActiveExecutionRegistry(), new NoopResultStore());
    }

    public ToolRuntime(Config config,
                       SessionManager sessionManager,
                       ExecutorService toolExecutionExecutor,
                       ToolExecutionStateTracker stateTracker,
                       ActiveExecutionRegistry activeExecutionRegistry) {
        this(config, sessionManager, toolExecutionExecutor, stateTracker, activeExecutionRegistry, new NoopResultStore());
    }

    public ToolRuntime(Config config,
                       SessionManager sessionManager,
                       ExecutorService toolExecutionExecutor,
                       ToolExecutionStateTracker stateTracker,
                       ActiveExecutionRegistry activeExecutionRegistry,
                       ResultStore resultStore) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.toolExecutionExecutor = toolExecutionExecutor;
        this.stateTracker = stateTracker;
        this.eventPublisher = new ToolEventPublisher();
        this.activeExecutionRegistry = activeExecutionRegistry;
        this.resultStore = resultStore != null ? resultStore : new NoopResultStore();
    }

    public ToolExecutionResult execute(ToolExecutionRequest executionRequest) {
        String toolId = executionRequest.toolName() + "_" + System.currentTimeMillis();
        long toolStartAt = System.currentTimeMillis();
        String truncatedRequest = truncateToolRequest(executionRequest.request());
        Throwable throwable = null;

        Message assistantToolMessage = Message.assistant("");
        assistantToolMessage.setToolCalls(List.of(
                new ToolCall(toolId, executionRequest.toolName(), executionRequest.request())
        ));
        sessionManager.addFullMessage(executionRequest.sessionKey(), assistantToolMessage);

        stateTracker.markExecuting(executionRequest.sessionKey());
        activeExecutionRegistry.toolStarted(executionRequest.sessionKey());
        eventPublisher.publishStart(
                executionRequest.eventCallback(),
                executionRequest.sessionKey(),
                executionRequest.toolName(),
                toolId,
                truncatedRequest
        );
        ScheduledFuture<?> progressFuture = scheduleProgressEvents(executionRequest, toolId, truncatedRequest, toolStartAt);

        try {
            DuplicateDecision duplicateDecision = evaluateDuplicateGuard(
                    executionRequest.sessionKey(),
                    executionRequest.toolName(),
                    executionRequest.request()
            );
            if (duplicateDecision.shortCircuit()) {
                long durationMs = System.currentTimeMillis() - toolStartAt;
                sessionManager.addFullMessage(executionRequest.sessionKey(), Message.tool(toolId, duplicateDecision.message()));
                eventPublisher.publishEnd(
                        executionRequest.eventCallback(),
                        executionRequest.sessionKey(),
                        executionRequest.toolName(),
                        toolId,
                        truncatedRequest,
                        durationMs
                );
                eventPublisher.publishOutput(
                        executionRequest.eventCallback(),
                        executionRequest.sessionKey(),
                        executionRequest.toolName(),
                        toolId,
                        truncatedRequest,
                        durationMs,
                        duplicateDecision.message().length(),
                        duplicateDecision.message()
                );
                return new ToolExecutionResult(toolId, duplicateDecision.message(), durationMs, true, null);
            }

            String response = callWithTimeout(
                    executionRequest.callback(),
                    executionRequest.request(),
                    executionRequest.toolName()
            );
            String modelResponse = prepareModelResponse(executionRequest, response);
            long durationMs = System.currentTimeMillis() - toolStartAt;
            sessionManager.addFullMessage(executionRequest.sessionKey(), Message.tool(toolId, modelResponse));

            if (isToolErrorResponse(response)) {
                eventPublisher.publishError(
                        executionRequest.eventCallback(),
                        executionRequest.sessionKey(),
                        executionRequest.toolName(),
                        toolId,
                        truncatedRequest,
                        durationMs,
                        response
                );
                return new ToolExecutionResult(toolId, modelResponse, durationMs, false, response);
            }

            eventPublisher.publishEnd(
                    executionRequest.eventCallback(),
                    executionRequest.sessionKey(),
                    executionRequest.toolName(),
                    toolId,
                    truncatedRequest,
                    durationMs
            );
            eventPublisher.publishOutput(
                    executionRequest.eventCallback(),
                    executionRequest.sessionKey(),
                    executionRequest.toolName(),
                    toolId,
                    truncatedRequest,
                    durationMs,
                    outputLength(response),
                    truncateToolOutput(response, executionRequest.toolName())
            );

            return new ToolExecutionResult(toolId, modelResponse, durationMs, true, null);
        } catch (Throwable e) {
            throwable = e;
            long durationMs = System.currentTimeMillis() - toolStartAt;
            sessionManager.addFullMessage(executionRequest.sessionKey(), Message.tool(toolId, "ERROR: " + e.getMessage()));
            eventPublisher.publishError(
                    executionRequest.eventCallback(),
                    executionRequest.sessionKey(),
                    executionRequest.toolName(),
                    toolId,
                    truncatedRequest,
                    durationMs,
                    e.getMessage()
            );
            throw asRuntimeException(e);
        } finally {
            progressFuture.cancel(false);
            stateTracker.markIdle(executionRequest.sessionKey());
            activeExecutionRegistry.toolFinished(executionRequest.sessionKey());
            if (throwable == null) {
                stateTracker.flushBufferedThink(executionRequest.sessionKey(), executionRequest.eventCallback());
            } else {
                stateTracker.clearBufferedThink(executionRequest.sessionKey());
            }
        }
    }

    private ScheduledFuture<?> scheduleProgressEvents(ToolExecutionRequest executionRequest,
                                                      String toolId,
                                                      String truncatedRequest,
                                                      long toolStartAt) {
        return TOOL_PROGRESS_EXECUTOR.scheduleAtFixedRate(
                () -> eventPublisher.publishProgress(
                        executionRequest.eventCallback(),
                        executionRequest.sessionKey(),
                        executionRequest.toolName(),
                        toolId,
                        truncatedRequest,
                        System.currentTimeMillis() - toolStartAt
                ),
                TOOL_PROGRESS_INTERVAL_MILLIS,
                TOOL_PROGRESS_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS
        );
    }

    private boolean isToolErrorResponse(String response) {
        return response != null && response.stripLeading().startsWith("Error:");
    }

    private String callWithTimeout(ToolCallback callback, String request, String toolName) {
        AgentExecutionContext.ExecutionScope capturedScope = AgentExecutionContext.getCurrentScope();
        Future<String> future = toolExecutionExecutor.submit(() -> {
            AgentExecutionContext.ExecutionScope previousScope = AgentExecutionContext.getCurrentScope();
            if (capturedScope != null) {
                AgentExecutionContext.setCurrentContext(capturedScope);
            }
            try {
                return callback.call(request);
            } finally {
                if (previousScope != null) {
                    AgentExecutionContext.setCurrentContext(previousScope);
                } else {
                    AgentExecutionContext.clear();
                }
            }
        });
        long timeoutMillis = resolveTimeoutMillis(toolName, request);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException("Tool '" + toolName + "' timed out after "
                    + timeoutMillis + " ms", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Tool '" + toolName + "' execution interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause != null ? asRuntimeException(cause) : new RuntimeException(e);
        }
    }

    private String truncateToolRequest(String request) {
        if (request == null || request.isBlank()) {
            return "";
        }
        int maxLength = Math.min(500, config.getAgent().getMaxToolOutputLength());
        if (request.length() <= maxLength) {
            return request;
        }
        return request.substring(0, maxLength) + "\n[request truncated]";
    }

    private String truncateToolOutput(String output, String toolName) {
        if (output == null) {
            return "无返回数据";
        }

        // This limit is only for UI/event display. The agent loop and persisted
        // tool message keep the original output so task execution is not weakened.
        int maxLength = config.getAgent().getMaxToolOutputLength();
        if (output.length() <= maxLength) {
            return output;
        }

        String truncated = output.substring(0, maxLength);
        String truncateNotice = "\n\n[... 返回结果已截断，共 " + output.length()
                + " 字符，显示前 " + maxLength + " 字符 ...]";

        logger.info("工具 {} 输出超长 ({} 字符)，已截断至 {} 字符",
                toolName, output.length(), maxLength);

        return truncated + truncateNotice;
    }

    private int outputLength(String output) {
        return output != null ? output.length() : 0;
    }

    private String prepareModelResponse(ToolExecutionRequest executionRequest, String response) {
        if (!shouldStoreAsReference(executionRequest.sessionKey(), executionRequest.toolName(), response)) {
            return response;
        }
        AgentExecutionContext.ExecutionScope scope = AgentExecutionContext.getCurrentScope();
        String runId = scope != null ? scope.runId() : null;
        ContextRef ref = resultStore.save(
                executionRequest.sessionKey(),
                runId,
                "tool",
                executionRequest.toolName(),
                response
        );
        return formatContextReferenceResponse(ref);
    }

    private boolean shouldStoreAsReference(String sessionKey, String toolName, String response) {
        if (response == null || response.isEmpty()) {
            return false;
        }
        if ("context_ref".equals(toolName)) {
            return false;
        }
        if (config == null || config.getAgent() == null || !config.getAgent().isContextRefEnabled()) {
            return false;
        }
        int threshold = Math.max(1, config.getAgent().getContextRefThresholdChars());
        if (response.length() > threshold) {
            return true;
        }
        return exceedsTurnBudget(sessionKey, toolName, response);
    }

    private boolean exceedsTurnBudget(String sessionKey, String toolName, String response) {
        if (config == null || config.getAgent() == null || !config.getAgent().isContextRefEnabled()) {
            return false;
        }
        if ("context_ref".equals(toolName)) {
            return false;
        }
        int budget = config.getAgent().getContextRefTurnBudgetChars();
        if (budget <= 0) {
            return false;
        }
        AgentExecutionContext.ExecutionScope scope = AgentExecutionContext.getCurrentScope();
        String runId = scope != null && scope.runId() != null ? scope.runId() : "no-run";
        String effectiveSessionKey = scope != null && scope.sessionKey() != null ? scope.sessionKey() : sessionKey;
        String key = (effectiveSessionKey != null ? effectiveSessionKey : "no-session") + ":" + runId;
        TurnBudgetState state = turnBudgetStates.computeIfAbsent(key, ignored -> new TurnBudgetState());
        return state.addAndExceeds(response.length(), budget);
    }

    private String formatContextReferenceResponse(ContextRef ref) {
        return toContextReferenceResponse(ref);
    }

    public static String toContextReferenceResponse(ContextRef ref) {
        return """
                Large tool result stored as a context reference.

                refId: %s
                source: %s
                contentLength: %d

                preview:
                %s

                Use context_ref(action='read', refId='%s', start='0', maxChars='12000') or context_ref(action='search', refId='%s', query='...') if you need more detail.
                """.formatted(
                ref.getRefId(),
                ref.getSourceName(),
                ref.getContentLength(),
                ref.getPreview() != null ? ref.getPreview() : "",
                ref.getRefId(),
                ref.getRefId()
        );
    }

    public void clearRunState(String sessionKey, String runId) {
        if (sessionKey != null && runId != null) {
            turnBudgetStates.remove(sessionKey + ":" + runId);
        }
    }

    private DuplicateDecision evaluateDuplicateGuard(String sessionKey, String toolName, String request) {
        if (!isRepeatGuardTool(toolName) || hasExplicitRefreshIntent(request)) {
            duplicateGuardStates.remove(sessionKey);
            return DuplicateDecision.pass();
        }

        String key = toolName + ":" + normalizeArguments(request);
        DuplicateGuardState state = duplicateGuardStates.compute(sessionKey, (ignored, previous) -> {
            if (previous == null || !previous.key().equals(key)) {
                return new DuplicateGuardState(key, 1);
            }
            return new DuplicateGuardState(key, previous.count() + 1);
        });

        if (state.count() >= DUPLICATE_BLOCK_COUNT) {
            return DuplicateDecision.block("""
                    Duplicate read/search call blocked.

                    This exact safe read/search tool call was requested %d times consecutively. Reuse the existing tool result in the conversation, use context_ref if a refId was returned, or change the arguments if new information is required.
                    """.formatted(state.count()));
        }
        if (state.count() == DUPLICATE_WARNING_COUNT) {
            return DuplicateDecision.block("""
                    Duplicate read/search call warning.

                    This exact safe read/search tool call was requested 3 times consecutively. Check the previous tool result before calling it again. If a fresh read is truly required, call again with an explicit refresh/reread reason in the arguments.
                    """);
        }
        return DuplicateDecision.pass();
    }

    private boolean isRepeatGuardTool(String toolName) {
        if (toolName == null) {
            return false;
        }
        String normalized = toolName.toLowerCase(Locale.ROOT);
        if (SAFE_REPEAT_GUARD_TOOLS.contains(normalized)) {
            return true;
        }
        return normalized.contains("read")
                || normalized.contains("search")
                || normalized.contains("list")
                || normalized.contains("grep");
    }

    private String normalizeArguments(String request) {
        if (request == null || request.isBlank()) {
            return "";
        }
        return request.replaceAll("\\s+", " ").trim();
    }

    private boolean hasExplicitRefreshIntent(String request) {
        if (request == null || request.isBlank()) {
            return false;
        }
        String normalized = request.toLowerCase(Locale.ROOT);
        return normalized.contains("refresh")
                || normalized.contains("reread")
                || normalized.contains("reload")
                || normalized.contains("force")
                || normalized.contains("重新读取")
                || normalized.contains("重读")
                || normalized.contains("刷新");
    }

    private record TurnBudgetState(java.util.concurrent.atomic.AtomicInteger totalChars) {
        private TurnBudgetState() {
            this(new java.util.concurrent.atomic.AtomicInteger());
        }

        boolean addAndExceeds(int length, int budget) {
            return totalChars.addAndGet(Math.max(0, length)) > budget;
        }
    }

    private record DuplicateGuardState(String key, int count) {
    }

    private record DuplicateDecision(boolean shortCircuit, String message) {
        static DuplicateDecision pass() {
            return new DuplicateDecision(false, "");
        }

        static DuplicateDecision block(String message) {
            return new DuplicateDecision(true, message);
        }
    }

    private RuntimeException asRuntimeException(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException(throwable);
    }

    private long resolveTimeoutMillis(String toolName, String request) {
        if ("spawn".equals(toolName) || "collaborate".equals(toolName)) {
            long childAgentTimeout = resolveChildAgentTimeoutMillis(request);
            long grace = Math.max(SPAWN_TIMEOUT_GRACE_MILLIS, childAgentTimeout / 10);
            return Math.max(MIN_TIMEOUT_MILLIS, childAgentTimeout + grace);
        }
        long configuredTimeout = Math.max(MIN_TIMEOUT_MILLIS, config.getAgent().getToolCallTimeoutSeconds() * 1_000L);
        if ("run_command".equals(toolName) || "exec".equals(toolName)) {
            return resolveCommandTimeoutMillis(request, configuredTimeout);
        }
        return configuredTimeout;
    }

    private long resolveCommandTimeoutMillis(String request, long configuredTimeoutMillis) {
        Long requestedTimeoutMillis = parsePositiveMillis(request, TIMEOUT_SECONDS_PATTERN, 1_000L);
        if (requestedTimeoutMillis == null) {
            requestedTimeoutMillis = parsePositiveMillis(request, TIMEOUT_MS_PATTERN, 1L);
        }
        if (requestedTimeoutMillis == null) {
            return configuredTimeoutMillis;
        }
        long withGrace = Math.min(MAX_REQUESTED_TOOL_TIMEOUT_MILLIS, requestedTimeoutMillis + COMMAND_TIMEOUT_GRACE_MILLIS);
        return Math.max(configuredTimeoutMillis, withGrace);
    }

    private Long parsePositiveMillis(String request, Pattern pattern, long multiplier) {
        if (request == null || request.isBlank()) {
            return null;
        }
        Matcher matcher = pattern.matcher(request);
        if (!matcher.find()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(matcher.group(1));
            if (parsed <= 0) {
                return null;
            }
            return Math.multiplyExact(parsed, multiplier);
        } catch (ArithmeticException | NumberFormatException ignored) {
            return MAX_REQUESTED_TOOL_TIMEOUT_MILLIS;
        }
    }

    private long resolveChildAgentTimeoutMillis(String request) {
        long configuredTimeout = config.getAgent().getChildAgentTimeoutMs();
        if (request != null && !request.isBlank()) {
            Matcher matcher = TIMEOUT_MS_PATTERN.matcher(request);
            if (matcher.find()) {
                try {
                    long parsed = Long.parseLong(matcher.group(1));
                    if (parsed > 0) {
                        return Math.max(parsed, configuredTimeout);
                    }
                } catch (NumberFormatException ignored) {
                    // Fall back to configured child-agent timeout.
                }
            }
        }
        return configuredTimeout;
    }
}
