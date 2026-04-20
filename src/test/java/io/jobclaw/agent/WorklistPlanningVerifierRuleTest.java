package io.jobclaw.agent;

import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.completion.DoneDefinition;
import io.jobclaw.agent.planning.TaskPlanningMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorklistPlanningVerifierRuleTest {

    private final WorklistPlanningVerifierRule rule = new WorklistPlanningVerifierRule();

    @Test
    void shouldFailWhenWorklistTaskHasNoTrackedSubtasks() {
        TaskHarnessRun run = new TaskHarnessRun("session", "run", "task");
        run.setPlanningMode(TaskPlanningMode.WORKLIST, "independent_batch_items");
        run.setDoneDefinition(worklistDoneDefinition());

        TaskHarnessVerificationResult result = rule.verify(run, "阶段性总结", null);

        assertFalse(result.success());
        assertTrue(result.reason().contains("no subtasks were planned"));
    }

    @Test
    void shouldPassWhenWorklistTaskHasTrackedSubtasks() {
        TaskHarnessRun run = new TaskHarnessRun("session", "run", "task");
        run.setPlanningMode(TaskPlanningMode.WORKLIST, "independent_batch_items");
        run.setDoneDefinition(worklistDoneDefinition());
        run.upsertPlannedSubtask("a.pdf", "a.pdf", java.util.Map.of());

        TaskHarnessVerificationResult result = rule.verify(run, "阶段性总结", null);

        assertTrue(result.success());
    }

    @Test
    void shouldPassWhenWorklistModeDoesNotRequireWorklistInDoneDefinition() {
        TaskHarnessRun run = new TaskHarnessRun("spawn-child", "run-child", "child task");
        run.setPlanningMode(TaskPlanningMode.WORKLIST, "legacy_mode");
        run.setDoneDefinition(new DoneDefinition(
                TaskPlanningMode.DIRECT,
                DeliveryType.BATCH_RESULTS,
                List.of(),
                List.of(),
                false,
                true,
                false,
                List.of()
        ));

        TaskHarnessVerificationResult result = rule.verify(run, "已完成当前子任务", null);

        assertTrue(result.success());
    }

    private DoneDefinition worklistDoneDefinition() {
        return new DoneDefinition(
                TaskPlanningMode.WORKLIST,
                DeliveryType.BATCH_RESULTS,
                List.of(),
                List.of(),
                true,
                true,
                false,
                List.of("worklist")
        );
    }
}
