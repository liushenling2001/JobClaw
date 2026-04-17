package io.jobclaw.runtime.tool;

import io.jobclaw.agent.ExecutionEvent;

import java.util.function.Consumer;

public interface ToolExecutionStateTracker {

    void markExecuting(String sessionKey);

    void markIdle(String sessionKey);

    boolean isExecuting(String sessionKey);

    void bufferThink(String sessionKey, String content);

    void flushBufferedThink(String sessionKey, Consumer<ExecutionEvent> eventCallback);

    void clearBufferedThink(String sessionKey);
}
