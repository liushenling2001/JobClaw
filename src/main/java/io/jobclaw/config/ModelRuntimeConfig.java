package io.jobclaw.config;

import java.util.Map;

/** Resolves optional limits declared for one model without changing global defaults. */
public final class ModelRuntimeConfig {

    private ModelRuntimeConfig() {
    }

    public static int contextWindow(Config config, String model) {
        ModelsConfig.ModelDefinition definition = findDefinition(config, model);
        if (definition != null && definition.getMaxContextSize() != null
                && definition.getMaxContextSize() > 0) {
            return definition.getMaxContextSize();
        }
        return config.getAgent().getContextWindow();
    }

    public static int maxTokens(Config config, String model) {
        ModelsConfig.ModelDefinition definition = findDefinition(config, model);
        if (definition != null && definition.getMaxTokens() != null
                && definition.getMaxTokens() > 0) {
            return definition.getMaxTokens();
        }
        return config.getAgent().getMaxTokens();
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
