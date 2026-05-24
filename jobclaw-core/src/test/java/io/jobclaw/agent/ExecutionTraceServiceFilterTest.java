package io.jobclaw.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionTraceServiceFilterTest {

    @Test
    void shouldFilterHistoryByRunId() {
        ExecutionTraceService service = new ExecutionTraceService();
        service.publish(new ExecutionEvent("session-a", ExecutionEvent.EventType.CUSTOM, "r1-a", Map.of(), "run-1", null, "a", "A"));
        service.publish(new ExecutionEvent("session-a", ExecutionEvent.EventType.CUSTOM, "r2-a", Map.of(), "run-2", null, "b", "B"));
        service.publish(new ExecutionEvent("session-a", ExecutionEvent.EventType.CUSTOM, "r1-b", Map.of(), "run-1", null, "a", "A"));

        var run1 = service.getHistoryByRun("session-a", "run-1", 10);
        assertEquals(2, run1.size());
        assertTrue(run1.stream().allMatch(event -> "run-1".equals(event.getRunId())));
    }

    @Test
    void shouldKeepCustomEventsQueryableByRunId() {
        ExecutionTraceService service = new ExecutionTraceService();
        service.publish(new ExecutionEvent(
                "session-a",
                ExecutionEvent.EventType.CUSTOM,
                "Agent run started",
                Map.of("source", "agent_loop", "label", "started"),
                "run-9",
                null,
                "agent_loop",
                "Agent Loop"
        ));

        var runEvents = service.getHistoryByRun("session-a", "run-9", 10);
        assertEquals(1, runEvents.size());
        assertEquals("agent_loop", runEvents.get(0).getMetadata().get("source"));
    }

    @Test
    void shouldFilterHistoryByBoardId() {
        ExecutionTraceService service = new ExecutionTraceService();
        service.publish(new ExecutionEvent("session-a", ExecutionEvent.EventType.CUSTOM, "b1", Map.of("boardId", "board-1")));
        service.publish(new ExecutionEvent("session-a", ExecutionEvent.EventType.CUSTOM, "b2", Map.of("boardId", "board-2")));
        service.publish(new ExecutionEvent("session-a", ExecutionEvent.EventType.CUSTOM, "b1-2", Map.of("boardId", "board-1")));

        var board1 = service.getHistoryByBoard("session-a", "board-1", 10);
        assertEquals(2, board1.size());
        assertTrue(board1.stream().allMatch(event -> "board-1".equals(event.getMetadata().get("boardId"))));
    }

    @Test
    void shouldDropBrokenEmitterWithoutCompletingWithError() {
        ExecutionTraceService service = new ExecutionTraceService();
        BrokenEmitter emitter = new BrokenEmitter();
        service.subscribe("session-a", emitter);

        service.publish(new ExecutionEvent("session-a", ExecutionEvent.EventType.CUSTOM, "event", Map.of()));

        assertEquals(0, service.getSubscriberCount("session-a"));
        assertEquals(0, emitter.completeWithErrorCalls);
    }

    public static class BrokenEmitter {
        private int completeWithErrorCalls;

        public void send(Object data) throws IOException {
            throw new IOException("client disconnected");
        }

        public void completeWithError(Throwable ex) {
            completeWithErrorCalls++;
        }
    }
}
