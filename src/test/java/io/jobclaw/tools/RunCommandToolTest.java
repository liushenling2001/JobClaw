package io.jobclaw.tools;

import io.jobclaw.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RunCommandToolTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReturnErrorWhenCommandExitsNonZero() {
        RunCommandTool tool = new RunCommandTool(Config.defaultConfig());
        String command = System.getProperty("os.name").toLowerCase().contains("win")
                ? "exit /b 7"
                : "exit 7";

        String result = tool.execute(command, System.getProperty("user.dir"), 5);

        assertTrue(result.startsWith("Error: Command exited with code 7"));
    }

    @Test
    void shouldRejectLargeInlineScriptsSoAgentCanRepairWithScriptFile() {
        RunCommandTool tool = new RunCommandTool(Config.defaultConfig());
        StringBuilder command = new StringBuilder("python - <<EOF\n");
        for (int i = 0; i < 20; i++) {
            command.append("print('line ").append(i).append("')\n");
        }
        command.append("EOF");

        String result = tool.execute(command.toString(), System.getProperty("user.dir"), 5);

        assertTrue(result.startsWith("Error: Inline script command is too large"));
        assertTrue(result.contains("write_file"));
    }

    @Test
    void shouldRedactSecretsFromCommandOutput() {
        RunCommandTool tool = new RunCommandTool(Config.defaultConfig());
        String command = System.getProperty("os.name").toLowerCase().contains("win")
                ? "echo OPENAI_API_KEY=sk-test1234567890abcdef"
                : "printf 'OPENAI_API_KEY=sk-test1234567890abcdef\\n'";

        String result = tool.execute(command, System.getProperty("user.dir"), 5);

        assertTrue(result.contains("OPENAI_API_KEY=[REDACTED_SECRET]"));
        assertFalse(result.contains("sk-test1234567890abcdef"));
    }

    @Test
    void shouldRejectSensitiveCredentialFileReads() {
        RunCommandTool tool = new RunCommandTool(Config.defaultConfig());
        String command = System.getProperty("os.name").toLowerCase().contains("win")
                ? "type .env"
                : "cat .env";

        String result = tool.execute(command, System.getProperty("user.dir"), 5);

        assertTrue(result.startsWith("Error: Refusing to read sensitive credential file"));
    }

    @Test
    void shouldRejectDirectPowerShellSkillScriptWhenCmdWrapperExists() throws Exception {
        Path skillRoot = tempDir.resolve("sample-skill");
        Path scriptsDir = skillRoot.resolve("scripts");
        Files.createDirectories(scriptsDir);
        Files.writeString(skillRoot.resolve("SKILL.md"), "sample");
        Files.writeString(scriptsDir.resolve("run-task.cmd"), "@echo off");

        RunCommandTool tool = new RunCommandTool(Config.defaultConfig());
        String result = tool.execute(".\\scripts\\run-task.ps1 -Task demo", skillRoot.toString(), 5);

        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            assertTrue(result.startsWith("Error: Refusing to execute a PowerShell skill script directly"));
        }
    }

    @Test
    void shouldDetectSkillRootFromCdCommandBeforeRunningPowerShellScript() throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return;
        }

        Path skillRoot = tempDir.resolve("sample-skill");
        Path scriptsDir = skillRoot.resolve("scripts");
        Files.createDirectories(scriptsDir);
        Files.writeString(skillRoot.resolve("SKILL.md"), "sample");
        Files.writeString(scriptsDir.resolve("run-task.cmd"), "@echo off");

        RunCommandTool tool = new RunCommandTool(Config.defaultConfig());
        String result = tool.execute(
                "cd /d \"" + skillRoot + "\" && .\\scripts\\run-task.ps1 -Task demo",
                System.getProperty("user.dir"),
                5
        );

        assertTrue(result.startsWith("Error: Refusing to execute a PowerShell skill script directly"));
    }

    @Test
    void shouldRejectSkillImplementationMutationFromRuntimeCommand() throws Exception {
        Path skillRoot = tempDir.resolve("sample-skill");
        Files.createDirectories(skillRoot.resolve("scripts"));
        Files.writeString(skillRoot.resolve("SKILL.md"), "sample");

        RunCommandTool tool = new RunCommandTool(Config.defaultConfig());
        String result = tool.execute("Set-Content scripts/run_task.py fixed", skillRoot.toString(), 5);

        assertTrue(result.startsWith("Error: Refusing to modify skill implementation files"));
    }

    @Test
    void shouldRejectSkillImplementationMutationAfterCdIntoSkillRoot() throws Exception {
        Path skillRoot = tempDir.resolve("sample-skill");
        Files.createDirectories(skillRoot.resolve("scripts"));
        Files.writeString(skillRoot.resolve("SKILL.md"), "sample");

        RunCommandTool tool = new RunCommandTool(Config.defaultConfig());
        String result = tool.execute(
                "cd /d \"" + skillRoot + "\" && Set-Content scripts/run_task.py fixed",
                System.getProperty("user.dir"),
                5
        );

        assertTrue(result.startsWith("Error: Refusing to modify skill implementation files"));
    }

    @Test
    void shouldAllowRunningSkillEntrypointWithOutputRedirectOutsideProtectedPaths() throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return;
        }

        Path skillRoot = tempDir.resolve("sample-skill");
        Path scriptsDir = skillRoot.resolve("scripts");
        Files.createDirectories(scriptsDir);
        Files.writeString(skillRoot.resolve("SKILL.md"), "sample");
        Files.writeString(scriptsDir.resolve("run-task.cmd"), "@echo off\r\necho ok\r\n");

        RunCommandTool tool = new RunCommandTool(Config.defaultConfig());
        String result = tool.execute(".\\scripts\\run-task.cmd > run.log", skillRoot.toString(), 5);

        assertFalse(result.startsWith("Error: Refusing to modify skill implementation files"));
    }
}
