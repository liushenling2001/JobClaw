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
        assertEquals(ModelsConfig.ModelDefinition.DEFAULT_MAX_CONTEXT_SIZE,
                ModelRuntimeConfig.contextWindow(config, "unconfigured-model"));
        assertEquals(ModelsConfig.ModelDefinition.DEFAULT_MAX_TOKENS,
                ModelRuntimeConfig.maxTokens(config, "unconfigured-model"));
    }

    @Test
    void normalizesMissingCapacityIntoModelDefinitionWithoutUsingAgentLimits() {
        Config config = Config.defaultConfig();
        config.getAgent().setModel("custom-model");
        config.getAgent().setProvider("openai");
        config.getAgent().setContextWindow(999_999);
        config.getAgent().setMaxTokens(888_888);
        ModelsConfig.ModelDefinition definition = new ModelsConfig.ModelDefinition();
        definition.setProvider("openai");
        definition.setModel("custom-model");
        definition.setMaxContextSize(null);
        definition.setMaxTokens(null);
        config.getModels().getDefinitions().put("custom-model", definition);

        ModelRuntimeConfig.normalizeDefinitions(config);

        assertEquals(ModelsConfig.ModelDefinition.DEFAULT_MAX_CONTEXT_SIZE,
                ModelRuntimeConfig.contextWindow(config, "custom-model"));
        assertEquals(ModelsConfig.ModelDefinition.DEFAULT_MAX_TOKENS,
                ModelRuntimeConfig.maxTokens(config, "custom-model"));
    }
}
