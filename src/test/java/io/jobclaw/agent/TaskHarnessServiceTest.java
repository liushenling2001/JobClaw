package io.jobclaw.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskHarnessServiceTest {

    private final TaskHarnessVerifier verifier =
            new CompositeTaskHarnessVerifier(java.util.List.of(new DefaultTaskHarnessVerifier()));

    @Test
    void shouldTrackTransitionsAndVerification() {
        TaskHarnessService service = new TaskHarnessService();
        TaskHarnessRun run = service.startRun("session-a", "run-1", "fix failing test", null);

        service.transition(run, TaskHarnessPhase.ACT, "running", "act", "Running task", java.util.Map.of(), null);
        TaskHarnessVerificationResult result = service.verify(
                run,
                "Task completed",
                null,
                verifier,
                null
        );
        service.complete(run, result.success(), result.reason(), null);

        List<TaskHarnessStep> steps = run.getSteps();
        assertTrue(result.success());
        assertEquals(TaskHarnessPhase.FINISH, run.getCurrentPhase());
        assertTrue(steps.stream().anyMatch(step -> step.phase() == TaskHarnessPhase.PLAN));
        assertTrue(steps.stream().anyMatch(step -> step.phase() == TaskHarnessPhase.ACT));
        assertTrue(steps.stream().anyMatch(step -> step.phase() == TaskHarnessPhase.VERIFY));
        assertTrue(steps.stream().anyMatch(step -> step.phase() == TaskHarnessPhase.FINISH));
        assertNotNull(run.getCompletedAt());
    }

    @Test
    void shouldStoreVerificationReasonAndFinalResponseMetadata() {
        TaskHarnessService service = new TaskHarnessService();
        TaskHarnessRun run = service.startRun("session-a", "run-2", "fix failing test", null);

        TaskHarnessVerificationResult result = service.verify(
                run,
                "Error: compilation failed",
                null,
                verifier,
                null
        );

        TaskHarnessStep verifyFailed = run.getSteps().stream()
                .filter(step -> "verify_failed".equals(step.label()))
                .findFirst()
                .orElseThrow();

        assertTrue(!result.success());
        assertEquals("Error: compilation failed", verifyFailed.metadata().get("reason"));
        assertEquals("ERROR_RESPONSE", verifyFailed.metadata().get("failureType"));
        assertEquals("Error: compilation failed", verifyFailed.metadata().get("finalResponse"));
        assertEquals(result, run.getLastVerificationResult());
    }

    @Test
    void shouldStoreAndClearFailureStateAcrossCompletion() {
        TaskHarnessService service = new TaskHarnessService();
        TaskHarnessRun run = service.startRun("session-a", "run-3", "fix failing test", null);
        TaskHarnessFailure failure = new TaskHarnessFailure(
                TaskHarnessFailureKind.TOOL_FAILURE,
                "Shell command failed",
                "toolName=shell, request=mvn test"
        );

        service.recordFailure(run, failure, null);

        assertEquals(failure, run.getLastFailure());

        service.complete(run, true, "Recovered", null);

        assertNull(run.getLastFailure());
    }

    @Test
    void shouldTrackPendingSubtasksUntilCompleted() {
        TaskHarnessService service = new TaskHarnessService();
        TaskHarnessRun run = service.startRun("session-a", "run-4", "review multiple files", null);

        service.planSubtask(run.getRunId(), "a.txt", "File A", java.util.Map.of(), null);
        service.planSubtask(run.getRunId(), "b.txt", "File B", java.util.Map.of(), null);
        service.startSubtask(run.getRunId(), "a.txt", "File A", "spawn-1", java.util.Map.of(), null);
        service.completeSubtask(run.getRunId(), "a.txt", "done", true, java.util.Map.of(), null);

        assertEquals(1, run.getPendingSubtaskCount());
        assertEquals(2, run.getSubtasks().size());
        assertTrue(run.getSubtasks().stream().anyMatch(subtask -> subtask.id().equals("a.txt")
                && subtask.status() == TaskHarnessSubtaskStatus.COMPLETED));
    }
}
