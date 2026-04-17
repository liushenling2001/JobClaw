package io.jobclaw.tools;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.TaskHarnessRun;
import io.jobclaw.agent.TaskHarnessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubtasksToolTest {

    @AfterEach
    void tearDown() {
        AgentExecutionContext.clear();
    }

    @Test
    void shouldPlanAndCompleteTrackedSubtasksWithinCurrentRun() {
        TaskHarnessService service = new TaskHarnessService();
        TaskHarnessRun run = service.startRun("session-a", "run-1", "batch review", null);
        SubtasksTool tool = new SubtasksTool(service);
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "session-a",
                null,
                run.getRunId(),
                null,
                "assistant",
                "Assistant",
                null
        ));

        String planned = tool.subtasks("plan", null, null, "a.txt|File A\nb.txt|File B", null);
        String started = tool.subtasks("start", "a.txt", "File A", null, null);
        String completed = tool.subtasks("complete", "a.txt", null, null, "ok");
        String status = tool.subtasks("status", null, null, null, null);

        assertTrue(planned.contains("Planned 2 subtasks"));
        assertTrue(started.contains("Started subtask"));
        assertTrue(completed.contains("Completed subtask"));
        assertEquals(1, service.getRun(run.getRunId()).getPendingSubtaskCount());
        assertTrue(status.contains("Pending subtasks: 1"));
    }
}
