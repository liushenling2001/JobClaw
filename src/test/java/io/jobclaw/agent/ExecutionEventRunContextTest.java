package io.jobclaw.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionEventRunContextTest {

    @AfterEach
    void tearDown() {
        AgentExecutionContext.clear();
    }

    @Test
    void shouldAttachRunMetadataFromExecutionContext() {
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "web:test",
                null,
                "run-123",
                "run-parent",
                "jd-agent",
                "JD Agent",
                null
        ));

        ExecutionEvent event = new ExecutionEvent("web:test", ExecutionEvent.EventType.CUSTOM, "hello");

        assertEquals("run-123", event.getRunId());
        assertEquals("run-parent", event.getParentRunId());
        assertEquals("jd-agent", event.getAgentId());
        assertEquals("JD Agent", event.getAgentName());
    }
}
