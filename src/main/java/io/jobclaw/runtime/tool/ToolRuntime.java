package io.jobclaw.runtime.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.completion.ActiveExecutionRegistry;
import io.jobclaw.config.Config;
import io.jobclaw.context.result.ContextRef;
import io.jobclaw.context.result.NoopResultStore;
import io.jobclaw.context.result.ResultStore;
import io.jobclaw.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long MIN_TIMEOUT_MILLIS = 1_000L;
    private static final long SPAWN_TIMEOUT_GRACE_MILLIS = 30_000L;
    private static final long COMMAND_TIMEOUT_GRACE_MILLIS = 10_000L;
    private static final long MAX_REQUESTED_TOOL_TIMEOUT_MILLIS = TimeUnit.HOURS.toMillis(6);
    private static final long TOOL_PROGRESS_INTERVAL_MILLIS = 15_000L;
    private static final Pattern TIMEOUT_MS_PATTERN = Pattern.compile("\"?timeoutMs\"?\\s*[:=]\\s*(\\d+)");
    private static final Pattern TIMEOUT_SECONDS_PATTERN = Pattern.compile("\"?timeout(?:Seconds)?\"?\\s*[:=]\\s*(\\d+)");
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
        String requestSummary = summarizeToolRequest(executionRequest.toolName(), executionRequest.request());
        AgentExecutionContext.ExecutionScope startScope = AgentExecutionContext.getCurrentScope();
        Throwable throwable = null;

        logger.info("tool call start session={} run={} parentRun={} tool={} toolId={} requestChars={} request={}",
                executionRequest.sessionKey(),
                runId(startScope),
                parentRunId(startScope),
                executionRequest.toolName(),
                toolId,
                charLength(executionRequest.request()),
                requestSummary);

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
            String response = callWithTimeout(
                    executionRequest.callback(),
                    executionRequest.request(),
                    executionRequest.toolName()
            );
            String modelResponse = prepareModelResponse(executionRequest, response);
            long durationMs = System.currentTimeMillis() - toolStartAt;
            boolean externalized = isContextReferenceResponse(modelResponse);
            String refId = externalized ? extractRefId(modelResponse) : null;

            if (isToolErrorResponse(response)) {
                logger.warn("tool call error-response session={} run={} tool={} toolId={} durationMs={} request={} responseChars={} modelChars={} externalized={} refId={} error={}",
                        executionRequest.sessionKey(),
                        currentRunId(),
                        executionRequest.toolName(),
                        toolId,
                        durationMs,
                        requestSummary,
                        charLength(response),
                        charLength(modelResponse),
                        externalized,
                        refId,
                        sanitizeForLog(response, 500));
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

            logger.info("tool call end session={} run={} tool={} toolId={} success=true durationMs={} requestChars={} responseChars={} modelChars={} externalized={} refId={} request={}",
                    executionRequest.sessionKey(),
                    currentRunId(),
                    executionRequest.toolName(),
                    toolId,
                    durationMs,
                    charLength(executionRequest.request()),
                    charLength(response),
                    charLength(modelResponse),
                    externalized,
                    refId,
                    requestSummary);

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
            String errorResponse = "Error: " + safeErrorMessage(e);
            logger.warn("tool call exception session={} run={} tool={} toolId={} durationMs={} request={} error={}",
                    executionRequest.sessionKey(),
                    currentRunId(),
                    executionRequest.toolName(),
                    toolId,
                    durationMs,
                    requestSummary,
                    e.toString());
            eventPublisher.publishError(
                    executionRequest.eventCallback(),
                    executionRequest.sessionKey(),
                    executionRequest.toolName(),
                    toolId,
                    truncatedRequest,
                    durationMs,
                    errorResponse
            );
            return new ToolExecutionResult(toolId, errorResponse, durationMs, false, errorResponse);
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

    private String safeErrorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return throwable.getClass().getSimpleName();
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
        logger.info("context_ref stored session={} run={} sourceType=tool sourceName={} refId={} contentLength={}",
                executionRequest.sessionKey(),
                runId,
                executionRequest.toolName(),
                ref.getRefId(),
                ref.getContentLength());
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
            logger.debug("tool runtime run state cleared session={} run={}", sessionKey, runId);
        }
    }

    private String summarizeToolRequest(String toolName, String request) {
        if (request == null || request.isBlank()) {
            return "";
        }
        Map<String, Object> args = parseJsonObject(request);
        if (args == null || args.isEmpty()) {
            return sanitizeForLog(request, 1_000);
        }

        String normalizedTool = toolName != null ? toolName.toLowerCase(Locale.ROOT) : "";
        Map<String, Object> summary = new LinkedHashMap<>();
        putIfPresent(summary, args, "action");
        putIfPresent(summary, args, "name");
        putIfPresent(summary, args, "path");
        putIfPresent(summary, args, "directory");
        putIfPresent(summary, args, "inputDir");
        putIfPresent(summary, args, "outputDir");
        putIfPresent(summary, args, "manifestId");
        putIfPresent(summary, args, "itemId");
        putIfPresent(summary, args, "refId");
        putIfPresent(summary, args, "query");
        putIfPresent(summary, args, "start");
        putIfPresent(summary, args, "maxChars");
        putIfPresent(summary, args, "timeoutMs");
        putIfPresent(summary, args, "timeoutSeconds");

        if (args.containsKey("items")) {
            summary.put("itemsCount", collectionSize(args.get("items")));
        }
        if (args.containsKey("columns")) {
            summary.put("columnsCount", collectionSize(args.get("columns")));
        }
        if (args.containsKey("fields")) {
            summary.put("fieldsCount", collectionSize(args.get("fields")));
        }
        if (args.containsKey("checks")) {
            summary.put("checksCount", collectionSize(args.get("checks")));
        }

        if (normalizedTool.contains("write") || normalizedTool.contains("append") || normalizedTool.contains("edit")) {
            putTextLength(summary, args, "content");
            putTextLength(summary, args, "oldText");
            putTextLength(summary, args, "newText");
        } else {
            putTextLength(summary, args, "text");
            putTextLength(summary, args, "message");
            putTextLength(summary, args, "prompt");
        }

        if (summary.isEmpty()) {
            summary.put("argKeys", args.keySet());
        }
        return sanitizeForLog(summary.toString(), 1_000);
    }

    private Map<String, Object> parseJsonObject(String request) {
        try {
            return OBJECT_MAPPER.readValue(request, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return null;
        }
    }

    private void putIfPresent(Map<String, Object> summary, Map<String, Object> args, String key) {
        if (args.containsKey(key)) {
            Object value = args.get(key);
            if (value instanceof String text) {
                summary.put(key, sanitizeForLog(text, 300));
            } else if (value != null && !(value instanceof Map<?, ?>) && !(value instanceof List<?>)) {
                summary.put(key, value);
            }
        }
    }

    private void putTextLength(Map<String, Object> summary, Map<String, Object> args, String key) {
        if (args.containsKey(key)) {
            Object value = args.get(key);
            summary.put(key + "Chars", value != null ? value.toString().length() : 0);
        }
    }

    private int collectionSize(Object value) {
        if (value instanceof List<?> list) {
            return list.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        if (value instanceof String text) {
            return text.isBlank() ? 0 : 1;
        }
        return value == null ? 0 : 1;
    }

    private int charLength(String value) {
        return value != null ? value.length() : 0;
    }

    private String currentRunId() {
        return runId(AgentExecutionContext.getCurrentScope());
    }

    private String runId(AgentExecutionContext.ExecutionScope scope) {
        return scope != null ? scope.runId() : null;
    }

    private String parentRunId(AgentExecutionContext.ExecutionScope scope) {
        return scope != null ? scope.parentRunId() : null;
    }

    private boolean isContextReferenceResponse(String response) {
        return response != null && response.startsWith("Large tool result stored as a context reference.");
    }

    private String extractRefId(String response) {
        if (response == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?m)^refId:\\s*(\\S+)").matcher(response);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String sanitizeForLog(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String sanitized = text
                .replaceAll("(?i)(api[_-]?key|access[_-]?token|token|secret|password|authorization)\\s*[:=]\\s*([^,}\\s]+)", "$1=<redacted>")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        if (sanitized.length() <= maxChars) {
            return sanitized;
        }
        return sanitized.substring(0, maxChars) + "...[truncated " + (sanitized.length() - maxChars) + " chars]";
    }

    private record TurnBudgetState(java.util.concurrent.atomic.AtomicInteger totalChars) {
        private TurnBudgetState() {
            this(new java.util.concurrent.atomic.AtomicInteger());
        }

        boolean addAndExceeds(int length, int budget) {
            return totalChars.addAndGet(Math.max(0, length)) > budget;
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
