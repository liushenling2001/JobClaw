package io.jobclaw.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelRuntimeConfigTest {

    @Test
    void appliesLimitsOnlyToMatchingModel() {
        Config config = Config.defaultConfig();
        config.getAgent().setContextWindow(128_000);
        config.getAgent().setMaxTokens(32_768);

        ModelsConfig.ModelDefinition qwen = new ModelsConfig.ModelDefinition(
                "openrouter", "qwen-flash-next", 100_000);
        qwen.setMaxTokens(16_384);
        config.getModels().getDefinitions().put("qwen-flash-next", qwen);

        assertEquals(100_000, ModelRuntimeConfig.contextWindow(config, "qwen-flash-next"));
        assertEquals(16_384, ModelRuntimeConfig.maxTokens(config, "qwen-flash-next"));
        assertEquals(128_000, ModelRuntimeConfig.contextWindow(config, "unconfigured-model"));
        assertEquals(32_768, ModelRuntimeConfig.maxTokens(config, "unconfigured-model"));
    }
}
