package io.jobclaw.agent;

import io.jobclaw.config.Config;
import org.springframework.stereotype.Component;

@Component
public class TaskHarnessRepairStrategy {

    private final Config config;

    public TaskHarnessRepairStrategy(Config config) {
        this.config = config;
    }

    public int maxAttempts(TaskHarnessRun run) {
        return Math.max(0, config.getAgent().getMaxRepairAttempts());
    }

    public String failureType(TaskHarnessRun run) {
        TaskHarnessFailure failure = run != null ? run.getLastFailure() : null;
        if (failure != null && failure.kind() != null) {
            return failure.kind().name();
        }
        return "UNKNOWN";
    }
}
