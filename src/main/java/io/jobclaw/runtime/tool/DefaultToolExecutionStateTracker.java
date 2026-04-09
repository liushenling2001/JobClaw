package io.jobclaw.runtime.tool;

import io.jobclaw.agent.ExecutionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class DefaultToolExecutionStateTracker implements ToolExecutionStateTracker {

    private static final Logger logger = LoggerFactory.getLogger(DefaultToolExecutionStateTracker.class);

    private final Map<String, Queue<String>> thinkStreamBuffer = new ConcurrentHashMap<>();
    private final Map<String, Boolean> toolExecutingState = new ConcurrentHashMap<>();

    @Override
    public void markExecuting(String sessionKey) {
        toolExecutingState.put(sessionKey, true);
        thinkStreamBuffer.computeIfAbsent(sessionKey, key -> new LinkedList<>());
    }

    @Override
    public void markIdle(String sessionKey) {
        toolExecutingState.remove(sessionKey);
    }

    @Override
    public boolean isExecuting(String sessionKey) {
        return toolExecutingState.getOrDefault(sessionKey, false);
    }

    @Override
    public void bufferThink(String sessionKey, String content) {
        thinkStreamBuffer.computeIfAbsent(sessionKey, key -> new LinkedList<>()).offer(content);
    }

    @Override
    public void flushBufferedThink(String sessionKey, Consumer<ExecutionEvent> eventCallback) {
        Queue<String> buffer = thinkStreamBuffer.get(sessionKey);
        if (buffer == null || buffer.isEmpty() || eventCallback == null) {
            return;
        }

        logger.debug("Flushing {} buffered THINK_STREAM events for session: {}", buffer.size(), sessionKey);
        String content;
        while ((content = buffer.poll()) != null) {
            eventCallback.accept(new ExecutionEvent(
                    sessionKey,
                    ExecutionEvent.EventType.THINK_STREAM,
                    content
            ));
        }
    }

    @Override
    public void clearBufferedThink(String sessionKey) {
        thinkStreamBuffer.remove(sessionKey);
    }
}
