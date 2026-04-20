package io.jobclaw.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskHarnessRepairPromptBuilderTest {

    @Test
    void shouldBuildRepairPromptWithStructuredToolAndVerificationEvidence() {
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
        run.addStep(TaskHarnessPhase.REPAIR, "failed", "verify_failed", "Error: mvn test failed", Map.of(
                "verified", false,
                "reason", "Error: mvn test failed",
                "failureType", "TEST_COMMAND",
                "finalResponse", "Error: mvn test failed"
        ));
        run.recordVerificationResult(TaskHarnessVerificationResult.fail("TEST_COMMAND", "Test output indicates failure"));

        TaskHarnessRepairPromptBuilder builder = new TaskHarnessRepairPromptBuilder();
        TaskHarnessFailure failure = builder.classify(run, "Error: mvn test failed", null);
        String prompt = builder.build(run, "fix the build", failure, 1);

        assertEquals(TaskHarnessFailureKind.TOOL_FAILURE, failure.kind());
        assertTrue(prompt.contains("Failure kind: TOOL_FAILURE"));
        assertTrue(prompt.contains("Verifier failure type: TEST_COMMAND"));
        assertTrue(prompt.contains("Focus on the failing test command."));
        assertTrue(prompt.contains("Recent tool error:"));
        assertTrue(prompt.contains("toolName=shell"));
        assertTrue(prompt.contains("request=mvn test"));
        assertTrue(prompt.contains("Verification failure:"));
        assertTrue(prompt.contains("verifier_final_response=Error: mvn test failed"));
        assertTrue(prompt.contains("Original task:\nfix the build"));
    }

    @Test
    void shouldMapVerifierFailureTypeToVerificationFailureKind() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-2", "write report");
        run.recordVerificationResult(TaskHarnessVerificationResult.fail(
                "FILE_EXPECTATION",
                "Expected file missing after write operation: reports/report.md"
        ));

        TaskHarnessRepairPromptBuilder builder = new TaskHarnessRepairPromptBuilder();
        TaskHarnessFailure failure = builder.classify(run, "Done", null);

        assertEquals(TaskHarnessFailureKind.VERIFICATION_FAILURE, failure.kind());
    }

    @Test
    void shouldAddFileExpectationRepairGuidance() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-3", "write report");
        run.recordVerificationResult(TaskHarnessVerificationResult.fail(
                "FILE_EXPECTATION",
                "Expected file missing after write operation: reports/report.md"
        ));

        TaskHarnessRepairPromptBuilder builder = new TaskHarnessRepairPromptBuilder();
        String prompt = builder.build(
                run,
                "write report",
                new TaskHarnessFailure(TaskHarnessFailureKind.VERIFICATION_FAILURE, "missing file", "reports/report.md"),
                1
        );

        assertTrue(prompt.contains("Verifier failure type: FILE_EXPECTATION"));
        assertTrue(prompt.contains("Focus on the missing or unchanged file."));
    }

    @Test
    void shouldAddWorklistPlanningRepairGuidance() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-4", "batch review files");
        run.recordVerificationResult(TaskHarnessVerificationResult.fail(
                "WORKLIST_NOT_PLANNED",
                "This task was classified as a worklist task but no subtasks were planned"
        ));

        TaskHarnessRepairPromptBuilder builder = new TaskHarnessRepairPromptBuilder();
        String prompt = builder.build(
                run,
                "batch review files",
                new TaskHarnessFailure(TaskHarnessFailureKind.VERIFICATION_FAILURE, "missing worklist", ""),
                1
        );

        assertTrue(prompt.contains("Verifier failure type: WORKLIST_NOT_PLANNED"));
        assertTrue(prompt.contains("create the missing worklist"));
        assertTrue(prompt.contains("subtasks(action='plan'"));
    }

    @Test
    void shouldAddFailedSubtaskRetryGuidanceFromCompletionDecision() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-5", "batch review files");
        run.markSubtaskCompleted("file-a.pdf", "timeout", false, Map.of(
                "failureType", "timeout",
                "retryable", true,
                "retryCount", 0
        ));
        run.addStep(TaskHarnessPhase.VERIFY, "decision", "completion_decision",
                "Retryable failed subtasks remain",
                Map.of("missingRequirements", "failed_subtasks_retryable"));

        TaskHarnessRepairPromptBuilder builder = new TaskHarnessRepairPromptBuilder();
        String prompt = builder.build(
                run,
                "batch review files",
                new TaskHarnessFailure(TaskHarnessFailureKind.VERIFICATION_FAILURE, "failed subtask", ""),
                1
        );

        assertTrue(prompt.contains("Verifier failure type: FAILED_SUBTASKS_RETRYABLE"));
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
                new TaskHarnessFailure(TaskHarnessFailureKind.VERIFICATION_FAILURE, "pending subtasks", ""),
                1
        );

        assertTrue(prompt.contains("Tracked subtasks: total=50, pending=50"));
        assertTrue(prompt.contains("10 more subtasks omitted"));
    }
}
