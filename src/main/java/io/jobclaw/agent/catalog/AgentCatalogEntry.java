package io.jobclaw.agent.catalog;

import io.jobclaw.agent.AgentDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AgentCatalogEntry(
        String agentId,
        String code,
        String displayName,
        String description,
        String systemPrompt,
        List<String> aliases,
        List<String> allowedTools,
        List<String> allowedSkills,
        Map<String, Object> modelConfig,
        String memoryScope,
        String status,
        String visibility,
        int version,
        Instant createdAt,
        Instant updatedAt
) {

    public AgentDefinition toDefinition() {
        AgentDefinition.Builder builder = AgentDefinition.builder()
                .code(code)
                .displayName(displayName)
                .systemPrompt(systemPrompt)
                .description(description);

        if (allowedTools != null) {
            allowedTools.forEach(builder::allowedTool);
        }
        if (allowedSkills != null) {
            allowedSkills.forEach(builder::allowedSkill);
        }
        if (modelConfig != null && !modelConfig.isEmpty()) {
            AgentDefinition.AgentConfig config = new AgentDefinition.AgentConfig();
            Object model = modelConfig.get("model");
            Object temperature = modelConfig.get("temperature");
            Object maxTokens = modelConfig.get("maxTokens");
            Object apiBase = modelConfig.get("apiBase");
            if (model instanceof String modelValue) {
                config.setModel(modelValue);
            }
            if (temperature instanceof Number temperatureValue) {
                config.setTemperature(temperatureValue.doubleValue());
            }
            if (maxTokens instanceof Number maxTokensValue) {
                config.setMaxTokens(maxTokensValue.intValue());
            }
            if (apiBase instanceof String apiBaseValue) {
                config.setApiBase(apiBaseValue);
            }
            modelConfig.forEach(config::setCustomSetting);
            builder.config(config);
        }
        return builder.build();
    }
}
