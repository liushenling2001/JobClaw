package io.jobclaw.tools;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileToolsWorkspaceRestrictionTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearExecutionContext() {
        AgentExecutionContext.clear();
    }

    @Test
    void shouldUseCurrentSessionWorkspaceAsSecurityBoundary() throws Exception {
        Path defaultWorkspace = Files.createDirectories(tempDir.resolve("default"));
        Path sessionWorkspace = Files.createDirectories(tempDir.resolve("session"));
        Path allowed = Files.writeString(sessionWorkspace.resolve("allowed.txt"), "allowed");
        Path blocked = Files.writeString(defaultWorkspace.resolve("blocked.txt"), "blocked");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(defaultWorkspace.toString());
        config.getAgent().setRestrictToWorkspace(true);
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "web:workspace", null, null, null, null, null, null, null, sessionWorkspace.toString()));
        FileTools tools = new FileTools(config);

        assertEquals("allowed", tools.readFile(allowed.toString()));
        String result = tools.readFile(blocked.toString());
        assertTrue(result.contains("Access denied"));
        assertTrue(result.contains(sessionWorkspace.toString()));
    }

    @Test
    void shouldRejectRelativeTraversalOutsideCurrentWorkspace() throws Exception {
        Path sessionWorkspace = Files.createDirectories(tempDir.resolve("session"));
        Files.writeString(tempDir.resolve("outside.txt"), "outside");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(sessionWorkspace.toString());
        config.getAgent().setRestrictToWorkspace(true);
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "web:workspace", null, null, null, null, null, null, null, sessionWorkspace.toString()));

        String result = new FileTools(config).readFile("../outside.txt");

        assertTrue(result.contains("Access denied"));
    }

    @Test
    void shouldApplyRestrictionChangesWithoutRecreatingTools() throws Exception {
        Path sessionWorkspace = Files.createDirectories(tempDir.resolve("session"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "outside");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(sessionWorkspace.toString());
        config.getAgent().setRestrictToWorkspace(true);
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "web:workspace", null, null, null, null, null, null, null, sessionWorkspace.toString()));
        FileTools tools = new FileTools(config);

        assertTrue(tools.readFile(outside.toString()).contains("Access denied"));

        config.getAgent().setRestrictToWorkspace(false);
        assertEquals("outside", tools.readFile(outside.toString()));
    }
}
