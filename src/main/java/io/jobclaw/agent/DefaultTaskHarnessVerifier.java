package io.jobclaw.agent;

import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

@Component
@Order(100)
public class DefaultTaskHarnessVerifier implements TaskHarnessVerifierRule {

    @Override
    public TaskHarnessVerificationResult verify(TaskHarnessRun run, String finalResponse, Throwable failure) {
        if (failure != null) {
            return TaskHarnessVerificationResult.fail(
                    "EXECUTION_FAILURE",
                    failure.getMessage() != null ? failure.getMessage() : "Unhandled execution failure"
            );
        }
        if (finalResponse == null || finalResponse.isBlank()) {
            return TaskHarnessVerificationResult.fail("EMPTY_RESPONSE", "Empty final response");
        }
        if (finalResponse.startsWith("Error:")) {
            return TaskHarnessVerificationResult.fail("ERROR_RESPONSE", finalResponse);
        }
        return TaskHarnessVerificationResult.ok("No explicit failure detected in final response");
    }
}
