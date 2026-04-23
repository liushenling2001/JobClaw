package io.jobclaw.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskHarnessRepairPromptBuilderTest {

    @Test
    void shouldBuildRepairPromptWithStructuredToolEvidence() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-1", "fix the build");
        run.addStep(TaskHarnessPhase.ACT, "event", "tool_start", "Running mvn test", Map.of(
                "eventType", "TOOL_START",
                "toolName", "shell",
                "toolId", "tool-1",
                "request", "mvn test"
        ));
        run.addStep(TaskHarnessPhase.REPAIR, "event", "tool_error", "mvn test failed with compilation error", Map.of(
                "eventType", "TOOL_ERROR",
                "toolName", "shell",
                "toolId", "tool-1",
                "request", "mvn test"
        ));
        TaskHarnessRepairPromptBuilder builder = new TaskHarnessRepairPromptBuilder();
        TaskHarnessFailure failure = builder.classify(run, "Error: mvn test failed", null);
        String prompt = builder.build(run, "fix the build", failure, 1);

        assertEquals(TaskHarnessFailureKind.TOOL_FAILURE, failure.kind());
        assertTrue(prompt.contains("Failure kind: TOOL_FAILURE"));
        assertTrue(prompt.contains("Failure type: TOOL_FAILURE"));
        assertTrue(prompt.contains("Focus on the failing tool invocation."));
        assertTrue(prompt.contains("Recent tool error:"));
        assertTrue(prompt.contains("toolName=shell"));
        assertTrue(prompt.contains("request=mvn test"));
        assertTrue(prompt.contains("Original task:\nfix the build"));
    }

    @Test
    void shouldAddFailedSubtaskRetryGuidanceFromCompletionDecision() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-5", "batch review files");
        run.markSubtaskCompleted("file-a.pdf", "timeout", false, Map.of(
                "failureType", "timeout",
                "retryable", true,
                "retryCount", 0
        ));
        run.addStep(TaskHarnessPhase.REVIEW, "decision", "completion_decision",
                "Retryable failed subtasks remain",
                Map.of("missingRequirements", "failed_subtasks_retryable"));

        TaskHarnessRepairPromptBuilder builder = new TaskHarnessRepairPromptBuilder();
        String prompt = builder.build(
                run,
                "batch review files",
                new TaskHarnessFailure(TaskHarnessFailureKind.OUTCOME_FAILURE, "failed subtask", ""),
                1
        );

        assertTrue(prompt.contains("Failure type: FAILED_SUBTASKS_RETRYABLE"));
        assertTrue(prompt.contains("Retry only the failed subtasks"));
        assertTrue(prompt.contains("file-a.pdf [FAILED]"));
        assertTrue(prompt.contains("failureType=timeout"));
    }

    @Test
    void shouldBoundLargeSubtaskEvidenceInRepairPrompt() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-6", "batch review files");
        for (int i = 1; i <= 50; i++) {
            run.upsertPlannedSubtask("file-" + i + ".pdf", "File " + i, Map.of("source", "test"));
        }

        TaskHarnessRepairPromptBuilder builder = new TaskHarnessRepairPromptBuilder();
        String prompt = builder.build(
                run,
                "batch review files",
                new TaskHarnessFailure(TaskHarnessFailureKind.OUTCOME_FAILURE, "pending subtasks", ""),
                1
        );

        assertTrue(prompt.contains("Tracked subtasks: total=50, pending=50"));
        assertTrue(prompt.contains("10 more subtasks omitted"));
    }
}
