package io.jobclaw.agent.completion;

import io.jobclaw.agent.TaskHarnessRun;
import io.jobclaw.agent.planning.TaskPlanningMode;
import io.jobclaw.config.Config;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskCompletionControllerTest {

    @Test
    void shouldRepairRetryableFailedSubtaskBeforeFinalSummary() {
        Config config = Config.defaultConfig();
        config.getAgent().setMaxSubtaskRepairAttempts(1);
        TaskHarnessRun run = worklistRun();
        run.markSubtaskCompleted("a.pdf", "timeout", false, Map.of(
                "failureType", "timeout",
                "retryable", true,
                "retryCount", 1
        ));

        TaskCompletionDecision decision = new TaskCompletionController(config)
                .evaluate(run, run.getDoneDefinition(), "最终汇总：a.pdf 失败。", null);

        assertEquals(TaskCompletionDecision.Status.REPAIR, decision.status());
        assertEquals(List.of("failed_subtasks_retryable"), decision.missingRequirements());
    }

    @Test
    void shouldAllowFinalSummaryWhenRetryLimitReached() {
        Config config = Config.defaultConfig();
        config.getAgent().setMaxSubtaskRepairAttempts(1);
        TaskHarnessRun run = worklistRun();
        run.markSubtaskCompleted("a.pdf", "timeout", false, Map.of(
                "failureType", "timeout",
                "retryable", true,
                "retryCount", 2
        ));

        TaskCompletionDecision decision = new TaskCompletionController(config)
                .evaluate(run, run.getDoneDefinition(), "最终汇总：a.pdf 失败，原因 timeout。", null);

        assertEquals(TaskCompletionDecision.Status.COMPLETE, decision.status());
    }

    @Test
    void shouldContinuePendingSubtasksEvenWhenWorklistWasOptional() {
        Config config = Config.defaultConfig();
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-child", "child batch task");
        run.setPlanningMode(TaskPlanningMode.DIRECT, "child_contract");
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
        run.upsertPlannedSubtask("nested-a", "Nested A", Map.of());

        TaskCompletionDecision decision = new TaskCompletionController(config)
                .evaluate(run, run.getDoneDefinition(), "最终汇总：处理中。", null);

        assertEquals(TaskCompletionDecision.Status.CONTINUE, decision.status());
        assertEquals(List.of("pending_subtasks"), decision.missingRequirements());
    }

    private TaskHarnessRun worklistRun() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-a", "批量审查 PDF");
        run.setPlanningMode(TaskPlanningMode.WORKLIST, "test");
        run.setDoneDefinition(new DoneDefinition(
                TaskPlanningMode.WORKLIST,
                DeliveryType.BATCH_RESULTS,
                List.of(),
                List.of(),
                true,
                true,
                false,
                List.of("worklist")
        ));
        return run;
    }
}
