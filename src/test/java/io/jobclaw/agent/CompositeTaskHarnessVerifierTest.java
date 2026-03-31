package io.jobclaw.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeTaskHarnessVerifierTest {

    @Test
    void shouldShortCircuitOnFirstFailedRule() {
        CompositeTaskHarnessVerifier verifier = new CompositeTaskHarnessVerifier(List.of(
                (run, finalResponse, failure) -> TaskHarnessVerificationResult.ok("first ok"),
                (run, finalResponse, failure) -> TaskHarnessVerificationResult.fail("second failed"),
                (run, finalResponse, failure) -> TaskHarnessVerificationResult.ok("third should not matter")
        ));

        TaskHarnessVerificationResult result = verifier.verify(
                new TaskHarnessRun("session-a", "run-1", "fix build"),
                "Task completed",
                null
        );

        assertFalse(result.success());
        assertEquals("second failed", result.reason());
    }

    @Test
    void shouldAcceptResponseWhenAllRulesPass() {
        CompositeTaskHarnessVerifier verifier = new CompositeTaskHarnessVerifier(List.of(
                (run, finalResponse, failure) -> TaskHarnessVerificationResult.ok("first ok"),
                (run, finalResponse, failure) -> TaskHarnessVerificationResult.ok("second ok")
        ));

        TaskHarnessVerificationResult result = verifier.verify(
                new TaskHarnessRun("session-a", "run-2", "fix build"),
                "Task completed",
                null
        );

        assertTrue(result.success());
        assertEquals("All verifiers accepted the final response", result.reason());
    }
}
