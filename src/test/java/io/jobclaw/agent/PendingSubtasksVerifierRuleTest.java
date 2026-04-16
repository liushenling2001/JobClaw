package io.jobclaw.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingSubtasksVerifierRuleTest {

    @Test
    void shouldRejectFinalResponseWhileSubtasksRemainPending() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-1", "review files");
        run.upsertPlannedSubtask("file-1", "File 1", java.util.Map.of());
        run.upsertPlannedSubtask("file-2", "File 2", java.util.Map.of());
        run.markSubtaskCompleted("file-1", "ok", true, java.util.Map.of());

        PendingSubtasksVerifierRule rule = new PendingSubtasksVerifierRule();
        TaskHarnessVerificationResult result = rule.verify(run, "已完成第一批，继续处理下一批", null);

        assertFalse(result.success());
        assertEquals("PENDING_SUBTASKS", result.failureType());
    }

    @Test
    void compositeVerifierShouldPassAfterAllSubtasksFinish() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-2", "review files");
        run.upsertPlannedSubtask("file-1", "File 1", java.util.Map.of());
        run.markSubtaskCompleted("file-1", "ok", true, java.util.Map.of());

        CompositeTaskHarnessVerifier verifier = new CompositeTaskHarnessVerifier(
                List.of(new PendingSubtasksVerifierRule(), new DefaultTaskHarnessVerifier())
        );
        TaskHarnessVerificationResult result = verifier.verify(run, "全部文件已审查完成", null);

        assertTrue(result.success());
    }
}
