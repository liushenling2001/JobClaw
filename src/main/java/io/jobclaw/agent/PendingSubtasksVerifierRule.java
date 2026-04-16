package io.jobclaw.agent;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class PendingSubtasksVerifierRule implements TaskHarnessVerifierRule {

    @Override
    public TaskHarnessVerificationResult verify(TaskHarnessRun run, String finalResponse, Throwable failure) {
        if (run == null || !run.hasTrackedSubtasks()) {
            return TaskHarnessVerificationResult.ok("No tracked subtasks");
        }
        int pending = run.getPendingSubtaskCount();
        if (pending <= 0) {
            return TaskHarnessVerificationResult.ok("All tracked subtasks completed");
        }
        return TaskHarnessVerificationResult.fail(
                "PENDING_SUBTASKS",
                "There are still " + pending + " pending subtasks"
        );
    }
}
