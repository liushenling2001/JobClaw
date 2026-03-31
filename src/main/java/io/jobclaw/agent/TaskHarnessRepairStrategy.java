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
        String failureType = failureType(run);
        if (failureType == null || failureType.isBlank() || "UNKNOWN".equals(failureType)) {
            return Math.max(0, config.getAgent().getMaxRepairAttempts());
        }
        int configured = switch (failureType) {
            case "FILE_EXPECTATION" -> config.getAgent().getMaxFileExpectationRepairAttempts();
            case "TEST_COMMAND" -> config.getAgent().getMaxTestCommandRepairAttempts();
            case "COMMAND_EXIT" -> config.getAgent().getMaxCommandExitRepairAttempts();
            default -> config.getAgent().getMaxVerificationRepairAttempts();
        };
        return Math.max(0, configured);
    }

    public String failureType(TaskHarnessRun run) {
        TaskHarnessVerificationResult verificationResult = run.getLastVerificationResult();
        if (verificationResult != null && verificationResult.failureType() != null
                && !verificationResult.failureType().isBlank()) {
            return verificationResult.failureType();
        }
        return "UNKNOWN";
    }
}
