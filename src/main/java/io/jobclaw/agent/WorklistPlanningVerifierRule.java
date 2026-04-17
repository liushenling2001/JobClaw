package io.jobclaw.agent;

import io.jobclaw.agent.planning.TaskPlanningMode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(9)
public class WorklistPlanningVerifierRule implements TaskHarnessVerifierRule {

    @Override
    public TaskHarnessVerificationResult verify(TaskHarnessRun run, String finalResponse, Throwable failure) {
        if (run == null || run.getPlanningMode() != TaskPlanningMode.WORKLIST) {
            return TaskHarnessVerificationResult.ok("Task does not require worklist planning");
        }
        if (run.hasTrackedSubtasks()) {
            return TaskHarnessVerificationResult.ok("Tracked subtasks exist for worklist task");
        }
        return TaskHarnessVerificationResult.fail(
                "WORKLIST_NOT_PLANNED",
                "This task was classified as a worklist task but no subtasks were planned"
        );
    }
}
