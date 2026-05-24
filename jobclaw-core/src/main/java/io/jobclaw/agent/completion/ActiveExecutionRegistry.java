package io.jobclaw.agent.completion;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ActiveExecutionRegistry {

    private static final ConcurrentHashMap<String, AtomicInteger> ACTIVE_TOOLS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicInteger> ACTIVE_SUBAGENTS = new ConcurrentHashMap<>();

    public void toolStarted(String sessionId) {
        increment(ACTIVE_TOOLS, sessionId);
    }

    public void toolFinished(String sessionId) {
        decrement(ACTIVE_TOOLS, sessionId);
    }

    public void subagentStarted(String sessionId) {
        increment(ACTIVE_SUBAGENTS, sessionId);
    }

    public void subagentFinished(String sessionId) {
        decrement(ACTIVE_SUBAGENTS, sessionId);
    }

    public int activeTools(String sessionId) {
        return get(ACTIVE_TOOLS, sessionId);
    }

    public int activeSubagents(String sessionId) {
        return get(ACTIVE_SUBAGENTS, sessionId);
    }

    private void increment(ConcurrentHashMap<String, AtomicInteger> store, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        store.computeIfAbsent(sessionId, key -> new AtomicInteger()).incrementAndGet();
    }

    private void decrement(ConcurrentHashMap<String, AtomicInteger> store, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        AtomicInteger counter = store.get(sessionId);
        if (counter == null) {
            return;
        }
        int next = counter.decrementAndGet();
        if (next <= 0) {
            store.remove(sessionId);
        }
    }

    private int get(ConcurrentHashMap<String, AtomicInteger> store, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        AtomicInteger counter = store.get(sessionId);
        return counter == null ? 0 : Math.max(0, counter.get());
    }
}
