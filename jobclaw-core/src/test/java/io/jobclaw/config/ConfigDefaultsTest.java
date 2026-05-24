package io.jobclaw.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDefaultsTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistRuntimeCriticalDefaults() throws Exception {
        Config config = Config.defaultConfig();
        Path configPath = tempDir.resolve("config.json");

        ConfigLoader.save(configPath.toString(), config);

        String json = Files.readString(configPath);
        assertTrue(json.contains("\"experience\""));
        assertTrue(json.contains("\"llmCallTimeoutSeconds\""));
        assertTrue(json.contains("\"childAgentTimeoutMs\""));
        assertTrue(json.contains("\"childAgentResultMaxChars\""));
        assertTrue(json.contains("\"contextRefTurnBudgetChars\""));
        assertTrue(json.contains("\"baseUrl\""));
        assertTrue(json.contains("\"dashscope\""));
        assertFalse(json.contains("\"subtaskTimeoutMs\""));
        assertFalse(json.contains("\"maxSubtaskRepairAttempts\""));
        assertEquals(60, config.getAgent().getSummarizeTokenPercentage());
        assertEquals(16, config.getAgent().getRecentMessagesToKeep());
        assertEquals(60, config.getAgent().getContextMaxPromptTokenPercentage());
        assertEquals(50, config.getAgent().getContextLongInputPromptTokenPercentage());
        assertEquals(0, config.getAgent().getContextMaxHistoryRetrieval());
        assertEquals(4, config.getAgent().getContextMaxSummaryRetrieval());
        assertEquals(8, config.getAgent().getContextMaxMemoryRetrieval());
    }

    @Test
    void shouldLoadMissingOptionalSectionsWithDefaults() throws Exception {
        Path configPath = tempDir.resolve("config.json");
        Files.writeString(configPath, """
                {
                  "agent": {
                    "workspace": "E:/jobwork",
                    "provider": "ollama",
                    "model": "qwen-coder"
                  },
                  "providers": {
                    "ollama": {
                      "baseUrl": "http://localhost:11434/v1"
                    }
                  }
                }
                """);

        Config config = ConfigLoader.load(configPath.toString());

        assertNotNull(config.getExperience());
        assertNotNull(config.getTools());
        assertNotNull(config.getGateway());
        assertNotNull(config.getMcpServers());
    }
}
