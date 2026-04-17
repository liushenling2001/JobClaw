package io.jobclaw.agent;

import io.jobclaw.agent.planning.TaskPlanningMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorklistPlanningVerifierRuleTest {

    private final WorklistPlanningVerifierRule rule = new WorklistPlanningVerifierRule();

    @Test
    void shouldFailWhenWorklistTaskHasNoTrackedSubtasks() {
        TaskHarnessRun run = new TaskHarnessRun("session", "run", "task");
        run.setPlanningMode(TaskPlanningMode.WORKLIST, "independent_batch_items");

        TaskHarnessVerificationResult result = rule.verify(run, "阶段性总结", null);

        assertFalse(result.success());
        assertTrue(result.reason().contains("no subtasks were planned"));
    }

    @Test
    void shouldPassWhenWorklistTaskHasTrackedSubtasks() {
        TaskHarnessRun run = new TaskHarnessRun("session", "run", "task");
        run.setPlanningMode(TaskPlanningMode.WORKLIST, "independent_batch_items");
        run.upsertPlannedSubtask("a.pdf", "a.pdf", java.util.Map.of());

        TaskHarnessVerificationResult result = rule.verify(run, "阶段性总结", null);

        assertTrue(result.success());
    }
}
