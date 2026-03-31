package io.jobclaw.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandExitExpectationRuleTest {

    private final CommandExitExpectationRule rule = new CommandExitExpectationRule();

    @Test
    void shouldFailWhenCommandOutputShowsNonZeroExit() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-1", "run a command");
        run.addStep(TaskHarnessPhase.OBSERVE, "event", "tool_output", "some output\nExit code: 1", Map.of(
                "eventType", "TOOL_OUTPUT",
                "toolName", "run_command",
                "toolId", "tool-1",
                "request", "mvn package"
        ));

        TaskHarnessVerificationResult result = rule.verify(run, "Done", null);

        assertFalse(result.success());
        assertEquals("COMMAND_EXIT", result.failureType());
    }

    @Test
    void shouldIgnoreNonCommandTools() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-2", "write a file");
        run.addStep(TaskHarnessPhase.OBSERVE, "event", "tool_output", "Exit code: 1", Map.of(
                "eventType", "TOOL_OUTPUT",
                "toolName", "write_file",
                "toolId", "tool-1",
                "request", "{\"path\":\"a.txt\"}"
        ));

        TaskHarnessVerificationResult result = rule.verify(run, "Done", null);

        assertTrue(result.success());
    }
}
