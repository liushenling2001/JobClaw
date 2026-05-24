package io.jobclaw.tools;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileToolsProjectRootTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearContext() {
        AgentExecutionContext.clear();
    }

    @Test
    void relativeWritesUseProjectRootWhenExecutionScopeProvidesOne() {
        Path stateRoot = tempDir.resolve("state");
        Path projectRoot = tempDir.resolve("project");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(stateRoot.toString());
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "session",
                null,
                "run-test",
                null,
                "assistant",
                "Assistant",
                null,
                projectRoot.toString(),
                projectRoot.toString(),
                "cli",
                "ask",
                "workspace-write"
        ));

        FileTools tools = new FileTools(config);
        String result = tools.writeFile("out/result.txt", "hello");

        assertTrue(result.startsWith("Successfully wrote"));
        assertTrue(Files.exists(projectRoot.resolve("out").resolve("result.txt")));
        assertFalse(Files.exists(stateRoot.resolve("out").resolve("result.txt")));
    }
}
