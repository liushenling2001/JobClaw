package io.jobclaw.runtime.tool;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.completion.ActiveExecutionRegistry;
import io.jobclaw.config.Config;
import io.jobclaw.providers.Message;
import io.jobclaw.providers.ToolCall;
import io.jobclaw.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ToolRuntime {

    private static final Logger logger = LoggerFactory.getLogger(ToolRuntime.class);
    private static final long MIN_TIMEOUT_MILLIS = 1_000L;

    private final Config config;
    private final SessionManager sessionManager;
    private final ExecutorService toolExecutionExecutor;
    private final ToolExecutionStateTracker stateTracker;
    private final ToolEventPublisher eventPublisher;
    private final ActiveExecutionRegistry activeExecutionRegistry;

    public ToolRuntime(Config config,
                       SessionManager sessionManager,
                       ExecutorService toolExecutionExecutor,
                       ToolExecutionStateTracker stateTracker) {
        this(config, sessionManager, toolExecutionExecutor, stateTracker, new ActiveExecutionRegistry());
    }

    public ToolRuntime(Config config,
                       SessionManager sessionManager,
                       ExecutorService toolExecutionExecutor,
                       ToolExecutionStateTracker stateTracker,
                       ActiveExecutionRegistry activeExecutionRegistry) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.toolExecutionExecutor = toolExecutionExecutor;
        this.stateTracker = stateTracker;
        this.eventPublisher = new ToolEventPublisher();
        this.activeExecutionRegistry = activeExecutionRegistry;
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

        try {
            String response = callWithTimeout(
                    executionRequest.callback(),
                    executionRequest.request(),
                    executionRequest.toolName()
            );
            long durationMs = System.currentTimeMillis() - toolStartAt;
            sessionManager.addFullMessage(executionRequest.sessionKey(), Message.tool(toolId, response));

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

            return new ToolExecutionResult(toolId, response, durationMs, true, null);
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
            stateTracker.markIdle(executionRequest.sessionKey());
            activeExecutionRegistry.toolFinished(executionRequest.sessionKey());
            if (throwable == null) {
                stateTracker.flushBufferedThink(executionRequest.sessionKey(), executionRequest.eventCallback());
            } else {
                stateTracker.clearBufferedThink(executionRequest.sessionKey());
            }
        }
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
        long timeoutMillis = resolveTimeoutMillis(toolName);
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

    private RuntimeException asRuntimeException(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException(throwable);
    }

    private long resolveTimeoutMillis(String toolName) {
        if ("spawn".equals(toolName) || "collaborate".equals(toolName)) {
            return Math.max(MIN_TIMEOUT_MILLIS, config.getAgent().getSubtaskTimeoutMs());
        }
        return Math.max(MIN_TIMEOUT_MILLIS, config.getAgent().getToolCallTimeoutSeconds() * 1_000L);
    }
}
