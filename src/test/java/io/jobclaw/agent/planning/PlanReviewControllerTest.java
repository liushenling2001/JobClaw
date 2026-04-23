package io.jobclaw.agent.planning;

import io.jobclaw.agent.TaskHarnessFailure;
import io.jobclaw.agent.TaskHarnessFailureKind;
import io.jobclaw.agent.TaskHarnessRun;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanReviewControllerTest {

    private final PlanReviewController controller = new PlanReviewController();

    @Test
    void shouldForceArtifactWhenResponseIsTooLarge() {
        TaskHarnessRun run = plannedRun();
        run.getPlanExecutionState().startCurrentStep();
        String largeResponse = "x".repeat(13_000);

        PlanReviewDecision decision = controller.evaluate(
                run,
                new TaskHarnessFailure(TaskHarnessFailureKind.OUTCOME_FAILURE, "too much context", ""),
                largeResponse
        );

        assertEquals(PlanReviewAction.FORCE_ARTIFACT, decision.action());
    }

    @Test
    void shouldDelegateWhenFailureNeedsIsolatedExecution() {
        TaskHarnessRun run = plannedRun();

        PlanReviewDecision decision = controller.evaluate(
                run,
                new TaskHarnessFailure(TaskHarnessFailureKind.EXECUTION_ERROR, "subagent timeout interrupted", ""),
                "still pending"
        );

        assertEquals(PlanReviewAction.DELEGATE_STEP, decision.action());
    }

    private TaskHarnessRun plannedRun() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-a", "根据多个文件完善报告");
        run.initializePlanExecution(List.of(
                new PlanStep("process-sources", "处理多个参考文件", "形成简短可用要点"),
                new PlanStep("update-target", "更新目标文件", "目标文件已落地")
        ));
        return run;
    }
}
