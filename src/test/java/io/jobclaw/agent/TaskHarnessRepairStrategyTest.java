package io.jobclaw.agent;

import io.jobclaw.config.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskHarnessRepairStrategyTest {

    @Test
    void shouldUseDefaultRepairBudgetWhenNoFailureIsRecorded() {
        Config config = Config.defaultConfig();
        TaskHarnessRepairStrategy strategy = new TaskHarnessRepairStrategy(config);
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-1", "write report");

        assertEquals(config.getAgent().getMaxRepairAttempts(), strategy.maxAttempts(run));
    }

    @Test
    void shouldUseDefaultRepairBudgetForRecordedOutcomeFailure() {
        Config config = Config.defaultConfig();
        TaskHarnessRepairStrategy strategy = new TaskHarnessRepairStrategy(config);
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-2", "do something");
        run.recordFailure(new TaskHarnessFailure(TaskHarnessFailureKind.OUTCOME_FAILURE,
                "Completion controller requested repair", ""));

        assertEquals(config.getAgent().getMaxRepairAttempts(), strategy.maxAttempts(run));
    }
}
