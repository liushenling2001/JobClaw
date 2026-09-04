package io.jobclaw.config;

import java.util.Map;

/** Resolves model capacity exclusively from the selected model definition. */
public final class ModelRuntimeConfig {

    private ModelRuntimeConfig() {
    }

    public static int contextWindow(Config config, String model) {
        ModelsConfig.ModelDefinition definition = findDefinition(config, model);
        if (definition != null && definition.getMaxContextSize() != null
                && definition.getMaxContextSize() > 0) {
            return definition.getMaxContextSize();
        }
        return ModelsConfig.ModelDefinition.DEFAULT_MAX_CONTEXT_SIZE;
    }

    public static int maxTokens(Config config, String model) {
        ModelsConfig.ModelDefinition definition = findDefinition(config, model);
        if (definition != null && definition.getMaxTokens() != null
                && definition.getMaxTokens() > 0) {
            return definition.getMaxTokens();
        }
        return ModelsConfig.ModelDefinition.DEFAULT_MAX_TOKENS;
    }

    public static void normalizeDefinitions(Config config) {
        if (config.getModels() == null) {
            config.setModels(new ModelsConfig());
        }
        Map<String, ModelsConfig.ModelDefinition> definitions = config.getModels().getDefinitions();
        if (definitions == null) {
            definitions = new java.util.LinkedHashMap<>();
            config.getModels().setDefinitions(definitions);
        }
        String activeModel = config.getAgent() != null ? config.getAgent().getModel() : null;
        String activeProvider = config.getAgent() != null ? config.getAgent().getProvider() : null;
        if (activeModel != null && !activeModel.isBlank() && findDefinition(config, activeModel) == null) {
            definitions.put(activeModel, new ModelsConfig.ModelDefinition(activeProvider, activeModel,
                    ModelsConfig.ModelDefinition.DEFAULT_MAX_CONTEXT_SIZE));
        }
        for (ModelsConfig.ModelDefinition definition : definitions.values()) {
            if (definition.getMaxContextSize() == null || definition.getMaxContextSize() <= 0) {
                definition.setMaxContextSize(ModelsConfig.ModelDefinition.DEFAULT_MAX_CONTEXT_SIZE);
            }
            if (definition.getMaxTokens() == null || definition.getMaxTokens() <= 0) {
                definition.setMaxTokens(ModelsConfig.ModelDefinition.DEFAULT_MAX_TOKENS);
            }
        }
    }

    static ModelsConfig.ModelDefinition findDefinition(Config config, String model) {
        if (config == null || config.getModels() == null
                || config.getModels().getDefinitions() == null || model == null || model.isBlank()) {
            return null;
        }
        Map<String, ModelsConfig.ModelDefinition> definitions = config.getModels().getDefinitions();
        ModelsConfig.ModelDefinition exact = definitions.get(model);
        if (exact != null) {
            return exact;
        }
        return definitions.values().stream()
                .filter(definition -> model.equals(definition.getModel()))
                .findFirst()
                .orElse(null);
    }
}
