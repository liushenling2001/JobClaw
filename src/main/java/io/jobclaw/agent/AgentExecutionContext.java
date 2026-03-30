package io.jobclaw.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Thread-local execution context shared between AgentLoop and tool callbacks.
 */
public class AgentExecutionContext {

    private static final Logger logger = LoggerFactory.getLogger(AgentExecutionContext.class);

    public record ExecutionScope(
            String sessionKey,
            Consumer<ExecutionEvent> eventCallback,
            String runId,
            String parentRunId,
            String agentId,
            String agentName
    ) {
    }

    private static final ThreadLocal<ExecutionScope> currentScope = new ThreadLocal<>();

    private AgentExecutionContext() {
    }

    public static void setCurrentContext(String sessionKey, Consumer<ExecutionEvent> callback) {
        if (sessionKey == null) {
            return;
        }
        setCurrentContext(new ExecutionScope(sessionKey, callback, null, null, null, null));
    }

    public static void setCurrentContext(ExecutionScope scope) {
        if (scope == null || scope.sessionKey() == null) {
            return;
        }
        currentScope.set(scope);
        logger.debug("Set execution context for session {} run {}", scope.sessionKey(), scope.runId());
    }

    public static ExecutionScope getCurrentScope() {
        return currentScope.get();
    }

    public static String getCurrentSessionKey() {
        ExecutionScope scope = currentScope.get();
        return scope != null ? scope.sessionKey() : null;
    }

    public static Consumer<ExecutionEvent> getCurrentEventCallback() {
        ExecutionScope scope = currentScope.get();
        return scope != null ? scope.eventCallback() : null;
    }

    public static String getCurrentRunId() {
        ExecutionScope scope = currentScope.get();
        return scope != null ? scope.runId() : null;
    }

    public static String getCurrentParentRunId() {
        ExecutionScope scope = currentScope.get();
        return scope != null ? scope.parentRunId() : null;
    }

    public static boolean hasContext() {
        return currentScope.get() != null;
    }

    public static void clear() {
        currentScope.remove();
        logger.debug("Cleared execution context");
    }

    public static boolean publishEvent(ExecutionEvent event) {
        Consumer<ExecutionEvent> callback = getCurrentEventCallback();
        if (callback == null) {
            return false;
        }
        try {
            callback.accept(event);
            return true;
        } catch (Exception e) {
            logger.warn("Failed to publish event: {}", e.getMessage());
            return false;
        }
    }
}
