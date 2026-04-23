package io.jobclaw.tools;

import io.jobclaw.config.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCommandToolTest {

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
}
