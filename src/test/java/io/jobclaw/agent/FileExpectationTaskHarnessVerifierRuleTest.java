package io.jobclaw.agent;

import io.jobclaw.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileExpectationTaskHarnessVerifierRuleTest {

    @TempDir
    Path workspace;

    @Test
    void shouldFailWhenWrittenFileDoesNotExist() {
        FileExpectationTaskHarnessVerifierRule rule = new FileExpectationTaskHarnessVerifierRule(configWithWorkspace());
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-1", "write report.md");
        run.addStep(TaskHarnessPhase.ACT, "event", "tool_start", "Writing file", Map.of(
                "eventType", "TOOL_START",
                "toolName", "write_file",
                "toolId", "tool-1",
                "request", "{\"path\":\"reports/report.md\",\"content\":\"hello\"}"
        ));

        TaskHarnessVerificationResult result = rule.verify(run, "Done", null);

        assertFalse(result.success());
        assertEquals("FILE_EXPECTATION", result.failureType());
    }

    @Test
    void shouldPassWhenWrittenFileExists() throws Exception {
        Files.createDirectories(workspace.resolve("reports"));
        Files.writeString(workspace.resolve("reports/report.md"), "hello");

        FileExpectationTaskHarnessVerifierRule rule = new FileExpectationTaskHarnessVerifierRule(configWithWorkspace());
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-2", "write report.md");
        run.addStep(TaskHarnessPhase.ACT, "event", "tool_start", "Writing file", Map.of(
                "eventType", "TOOL_START",
                "toolName", "write_file",
                "toolId", "tool-1",
                "request", "{\"path\":\"reports/report.md\",\"content\":\"hello\"}"
        ));

        TaskHarnessVerificationResult result = rule.verify(run, "Done", null);

        assertTrue(result.success());
    }

    private Config configWithWorkspace() {
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(workspace.toString());
        return config;
    }
}
