package io.jobclaw.agent;

import io.jobclaw.config.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskHarnessRepairStrategyTest {

    @Test
    void shouldUseFileExpectationBudget() {
        Config config = Config.defaultConfig();
        TaskHarnessRepairStrategy strategy = new TaskHarnessRepairStrategy(config);
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-1", "write report");
        run.recordVerificationResult(TaskHarnessVerificationResult.fail(
                "FILE_EXPECTATION",
                "Expected file missing after write operation"
        ));

        assertEquals(2, strategy.maxAttempts(run));
    }

    @Test
    void shouldUseVerificationBudgetForUnknownVerificationFailure() {
        Config config = Config.defaultConfig();
        TaskHarnessRepairStrategy strategy = new TaskHarnessRepairStrategy(config);
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-2", "do something");
        run.recordVerificationResult(TaskHarnessVerificationResult.fail(
                "SOME_OTHER_FAILURE",
                "Verifier rejected the response"
        ));

        assertEquals(1, strategy.maxAttempts(run));
    }
}
