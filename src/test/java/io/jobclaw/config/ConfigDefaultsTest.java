package io.jobclaw.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDefaultsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistUnifiedContextPolicyWithoutLegacyAgentLimits() throws Exception {
        Config config = Config.defaultConfig();
        Path configPath = tempDir.resolve("config.json");

        ConfigLoader.save(configPath.toString(), config);

        String json = Files.readString(configPath);
        assertTrue(json.contains("\"compactionTriggerPercentage\" : 80"));
        assertTrue(json.contains("\"compactionRetainPercentage\" : 16"));
        assertTrue(json.contains("\"baseUrl\""));
        assertTrue(json.contains("\"dashscope\""));
        assertFalse(json.contains("\"experience\""));
        assertFalse(json.contains("\"llmCallTimeoutSeconds\""));
        assertFalse(json.contains("\"childAgentTimeoutMs\""));
        assertFalse(json.contains("\"contextRefTurnBudgetChars\""));
        assertFalse(json.contains("\"subtaskTimeoutMs\""));
        assertFalse(json.contains("\"maxSubtaskRepairAttempts\""));
        assertEquals(80, config.getAgent().getCompactionTriggerPercentage());
        assertEquals(16, config.getAgent().getCompactionRetainPercentage());
        assertFalse(json.contains("\"summarizeTokenPercentage\""));
        assertFalse(json.contains("\"recentMessagesToKeep\""));
        assertFalse(json.contains("\"contextMaxPromptTokenPercentage\""));
        assertFalse(json.contains("\"contextMaxHistoryRetrieval\""));
    }

    @Test
    void shouldCreateMinimalInitialConfig() throws Exception {
        Path configPath = tempDir.resolve("config.json");

        ConfigLoader.createInitial(configPath.toString());

        JsonNode root = MAPPER.readTree(Files.readString(configPath));
        assertEquals(3, root.size());
        assertTrue(root.has("models"));
        assertTrue(root.has("agent"));
        assertTrue(root.has("providers"));

        JsonNode agent = root.path("agent");
        assertEquals(6, agent.size());
        assertEquals("~/.jobclaw/workspace", agent.path("workspace").asText());
        assertEquals("qwen3.5-plus", agent.path("model").asText());
        assertEquals("dashscope", agent.path("provider").asText());
        assertTrue(agent.path("restrictToWorkspace").asBoolean());
        assertEquals(80, agent.path("compactionTriggerPercentage").asInt());
        assertEquals(16, agent.path("compactionRetainPercentage").asInt());

        JsonNode definitions = root.path("models").path("definitions");
        assertEquals(1, definitions.size());
        assertEquals(128_000, definitions.path("qwen3.5-plus").path("maxContextSize").asInt());
        assertEquals(16_384, definitions.path("qwen3.5-plus").path("maxTokens").asInt());

        JsonNode providers = root.path("providers");
        assertEquals(1, providers.size());
        assertTrue(providers.has("dashscope"));
        assertFalse(providers.path("dashscope").has("streaming"));
        assertFalse(root.has("channels"));
        assertFalse(root.has("gateway"));
        assertFalse(root.has("tools"));
        assertFalse(root.has("experience"));

        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> ConfigLoader.createInitial(configPath.toString()));
    }

    @Test
    void shouldPersistOnlyCustomizedOptionalSettingsAndReloadThem() throws Exception {
        Config config = Config.defaultConfig();
        config.getAgent().setProvider("openai");
        config.getAgent().setModel("private-model");
        config.getAgent().setTemperature(0.2);
        config.getAgent().setMaxToolOutputLength(500);
        config.getAgent().setContextRefThresholdChars(999);
        config.getProviders().getOpenai().setApiBase("http://model-server/v1");
        config.getProviders().getOpenai().setApiKey("test-key");
        ModelsConfig.ModelDefinition model = new ModelsConfig.ModelDefinition(
                "openai", "private-model", 100_000);
        model.setMaxTokens(8_192);
        config.getModels().getDefinitions().put("private-model", model);
        config.getChannels().getTelegram().setEnabled(true);
        config.getChannels().getTelegram().setToken("test-token");
        config.getGateway().setPort(19_001);
        config.getExperience().setMaxInjectedMemories(4);
        Path configPath = tempDir.resolve("custom.json");

        ConfigLoader.save(configPath.toString(), config);
        Config loaded = ConfigLoader.load(configPath.toString());
        String saved = Files.readString(configPath);

        assertEquals("openai", loaded.getAgent().getProvider());
        assertEquals("private-model", loaded.getAgent().getModel());
        assertEquals(0.2, loaded.getAgent().getTemperature());
        assertEquals("test-key", loaded.getProviders().getOpenai().getApiKey());
        assertEquals(100_000, ModelRuntimeConfig.contextWindow(loaded, "private-model"));
        assertEquals(8_192, ModelRuntimeConfig.maxTokens(loaded, "private-model"));
        assertTrue(loaded.getChannels().getTelegram().isEnabled());
        assertEquals("test-token", loaded.getChannels().getTelegram().getToken());
        assertEquals(19_001, loaded.getGateway().getPort());
        assertEquals(4, loaded.getExperience().getMaxInjectedMemories());
        assertFalse(saved.contains("\"discord\""));
        assertFalse(saved.contains("\"socialNetwork\""));
        assertFalse(saved.contains("\"mcpServers\""));
        assertFalse(saved.contains("\"maxToolOutputLength\""));
        assertFalse(saved.contains("\"contextRefThresholdChars\""));
    }

    @Test
    void shouldDropLegacyUnknownFieldsWhenConfigurationIsSaved() throws Exception {
        Path configPath = tempDir.resolve("legacy.json");
        Files.writeString(configPath, """
                {
                  "agent": {
                    "workspace": "E:/jobwork",
                    "provider": "ollama",
                    "model": "qwen-coder",
                    "maxToolIterations": 99,
                    "summarizeMessageThreshold": 20,
                    "contextMaxPromptTokenPercentage": 60
                  },
                  "providers": {
                    "ollama": { "baseUrl": "http://localhost:11434/v1" }
                  }
                }
                """);

        Config config = ConfigLoader.load(configPath.toString());
        ConfigLoader.save(configPath.toString(), config);
        String saved = Files.readString(configPath);

        assertFalse(saved.contains("maxToolIterations"));
        assertFalse(saved.contains("summarizeMessageThreshold"));
        assertFalse(saved.contains("contextMaxPromptTokenPercentage"));
        assertTrue(saved.contains("compactionTriggerPercentage"));
        assertTrue(saved.contains("compactionRetainPercentage"));
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
