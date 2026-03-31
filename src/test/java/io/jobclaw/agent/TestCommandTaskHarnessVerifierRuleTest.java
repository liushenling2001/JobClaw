package io.jobclaw.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCommandTaskHarnessVerifierRuleTest {

    private final TestCommandTaskHarnessVerifierRule rule = new TestCommandTaskHarnessVerifierRule();

    @Test
    void shouldRequireSuccessEvidenceWhenTestCommandWasExecuted() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-1", "fix failing tests");
        run.addStep(TaskHarnessPhase.ACT, "event", "tool_start", "Running tests", Map.of(
                "eventType", "TOOL_START",
                "toolId", "tool-1",
                "toolName", "run_command",
                "request", "mvn test"
        ));

        TaskHarnessVerificationResult result = rule.verify(run, "I fixed the tests", null);

        assertFalse(result.success());
        assertEquals("TEST_COMMAND", result.failureType());
    }

    @Test
    void shouldFailWhenTestOutputContainsFailureMarkers() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-2", "fix failing tests");
        run.addStep(TaskHarnessPhase.ACT, "event", "tool_start", "Running tests", Map.of(
                "eventType", "TOOL_START",
                "toolId", "tool-1",
                "toolName", "run_command",
                "request", "pytest"
        ));
        run.addStep(TaskHarnessPhase.OBSERVE, "event", "tool_end", "Tool completed", Map.of(
                "eventType", "TOOL_END",
                "toolId", "tool-1",
                "toolName", "run_command",
                "request", "pytest"
        ));
        run.addStep(TaskHarnessPhase.OBSERVE, "event", "tool_output", "2 failed, 10 passed", Map.of(
                "eventType", "TOOL_OUTPUT",
                "toolId", "tool-1",
                "toolName", "run_command",
                "request", "pytest"
        ));

        TaskHarnessVerificationResult result = rule.verify(run, "I fixed the tests", null);

        assertFalse(result.success());
        assertEquals("TEST_COMMAND", result.failureType());
    }

    @Test
    void shouldPassWhenTestCommandHasSuccessEvidence() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-3", "fix failing tests");
        run.addStep(TaskHarnessPhase.ACT, "event", "tool_start", "Running tests", Map.of(
                "eventType", "TOOL_START",
                "toolId", "tool-1",
                "toolName", "run_command",
                "request", "mvn test"
        ));
        run.addStep(TaskHarnessPhase.OBSERVE, "event", "tool_end", "Tool completed", Map.of(
                "eventType", "TOOL_END",
                "toolId", "tool-1",
                "toolName", "run_command",
                "request", "mvn test"
        ));
        run.addStep(TaskHarnessPhase.OBSERVE, "event", "tool_output", "BUILD SUCCESS", Map.of(
                "eventType", "TOOL_OUTPUT",
                "toolId", "tool-1",
                "toolName", "run_command",
                "request", "mvn test"
        ));

        TaskHarnessVerificationResult result = rule.verify(run, "I fixed the tests", null);

        assertTrue(result.success());
    }
}
