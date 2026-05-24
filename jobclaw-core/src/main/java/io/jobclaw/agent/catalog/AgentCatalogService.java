package io.jobclaw.agent.catalog;

import io.jobclaw.agent.AgentDefinition;
import io.jobclaw.agent.AgentRole;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AgentCatalogService {

    private final AgentCatalogStore store;

    public AgentCatalogService(AgentCatalogStore store) {
        this.store = store;
    }

    public AgentCatalogEntry createAgent(String code,
                                         String displayName,
                                         String description,
                                         String systemPrompt,
                                         List<String> aliases,
                                         List<String> allowedTools,
                                         List<String> allowedSkills,
                                         Map<String, Object> modelConfig,
                                         String memoryScope) {
        Instant now = Instant.now();
        AgentCatalogEntry entry = new AgentCatalogEntry(
                "agent-" + code,
                code,
                displayName,
                description,
                systemPrompt,
                aliases != null ? aliases : List.of(),
                allowedTools != null ? allowedTools : List.of(),
                allowedSkills != null ? allowedSkills : List.of(),
                modelConfig != null ? modelConfig : Map.of(),
                memoryScope,
                "active",
                "private",
                1,
                now,
                now
        );
        return store.save(entry);
    }

    public AgentCatalogEntry createOrUpdateAgent(String code,
                                                 String displayName,
                                                 String description,
                                                 String systemPrompt,
                                                 List<String> aliases,
                                                 List<String> allowedTools,
                                                 List<String> allowedSkills,
                                                 Map<String, Object> modelConfig,
                                                 String memoryScope,
                                                 String status) {
        return resolve(code).map(existing -> {
            Instant now = Instant.now();
            AgentCatalogEntry updated = new AgentCatalogEntry(
                    existing.agentId(),
                    code,
                    displayName,
                    description,
                    systemPrompt,
                    aliases != null ? aliases : existing.aliases(),
                    allowedTools != null ? allowedTools : existing.allowedTools(),
                    allowedSkills != null ? allowedSkills : existing.allowedSkills(),
                    modelConfig != null ? modelConfig : existing.modelConfig(),
                    memoryScope != null ? memoryScope : existing.memoryScope(),
                    status != null ? status : existing.status(),
                    existing.visibility(),
                    existing.version() + 1,
                    existing.createdAt(),
                    now
            );
            return store.save(updated);
        }).orElseGet(() -> createAgent(
                code, displayName, description, systemPrompt, aliases, allowedTools, allowedSkills, modelConfig, memoryScope
        ));
    }

    public void ensureRoleAgentsExist(Map<String, Object> defaultModelConfig) {
        for (AgentRole role : AgentRole.values()) {
            if (role == AgentRole.ASSISTANT) {
                continue;
            }
            resolve(role.getCode()).ifPresentOrElse(existing -> {
                Map<String, Object> mergedModelConfig = mergeMissingModelConfig(existing.modelConfig(), defaultModelConfig);
                if (mergedModelConfig.equals(existing.modelConfig())) {
                    return;
                }
                createOrUpdateAgent(
                        role.getCode(),
                        existing.displayName(),
                        existing.description(),
                        existing.systemPrompt(),
                        existing.aliases(),
                        existing.allowedTools(),
                        existing.allowedSkills(),
                        mergedModelConfig,
                        existing.memoryScope(),
                        existing.status()
                );
            }, () -> createAgent(
                    role.getCode(),
                    role.getDisplayName(),
                    "Built-in role agent for " + role.getDisplayName(),
                    role.getSystemPrompt(),
                    List.of(role.getCode(), role.getDisplayName()),
                    List.of(),
                    List.of(),
                    mergeMissingModelConfig(Map.of(), defaultModelConfig),
                    "role:" + role.getCode()
            ));
        }
    }

    private Map<String, Object> mergeMissingModelConfig(Map<String, Object> existingModelConfig,
                                                        Map<String, Object> defaultModelConfig) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (existingModelConfig != null) {
            merged.putAll(existingModelConfig);
        }
        if (defaultModelConfig != null) {
            defaultModelConfig.forEach(merged::putIfAbsent);
        }
        return Map.copyOf(merged);
    }

    public Optional<AgentCatalogEntry> updateAgent(String nameOrAlias,
                                                   String displayName,
                                                   String description,
                                                   String systemPrompt,
                                                   List<String> aliases,
                                                   List<String> allowedTools,
                                                   List<String> allowedSkills,
                                                   Map<String, Object> modelConfig,
                                                   String memoryScope,
                                                   String status) {
        return resolve(nameOrAlias).map(existing -> {
            Instant now = Instant.now();
            AgentCatalogEntry updated = new AgentCatalogEntry(
                    existing.agentId(),
                    existing.code(),
                    displayName != null && !displayName.isBlank() ? displayName : existing.displayName(),
                    description != null && !description.isBlank() ? description : existing.description(),
                    systemPrompt != null && !systemPrompt.isBlank() ? systemPrompt : existing.systemPrompt(),
                    aliases != null ? aliases : existing.aliases(),
                    allowedTools != null ? allowedTools : existing.allowedTools(),
                    allowedSkills != null ? allowedSkills : existing.allowedSkills(),
                    modelConfig != null ? modelConfig : existing.modelConfig(),
                    memoryScope != null ? memoryScope : existing.memoryScope(),
                    status != null && !status.isBlank() ? status : existing.status(),
                    existing.visibility(),
                    existing.version() + 1,
                    existing.createdAt(),
                    now
            );
            return store.save(updated);
        });
    }

    public Optional<AgentCatalogEntry> resolve(String nameOrAlias) {
        if (nameOrAlias == null || nameOrAlias.isBlank()) {
            return Optional.empty();
        }
        return store.findByCode(nameOrAlias)
                .or(() -> store.findByAlias(nameOrAlias));
    }

    public List<AgentCatalogEntry> listAgents() {
        return store.listAgents();
    }

    public Optional<AgentDefinition> resolveDefinition(String nameOrAlias) {
        return resolve(nameOrAlias).map(AgentCatalogEntry::toDefinition);
    }

    public Optional<AgentCatalogEntry> get(String nameOrAlias) {
        return resolve(nameOrAlias);
    }

    public Optional<AgentCatalogEntry> updateAgent(String nameOrAlias,
                                                   String description,
                                                   String systemPrompt,
                                                   List<String> aliases,
                                                   List<String> allowedTools) {
        return updateAgent(nameOrAlias, null, description, systemPrompt,
                aliases != null && !aliases.isEmpty() ? aliases : null,
                allowedTools != null && !allowedTools.isEmpty() ? allowedTools : null,
                null, null, null, null);
    }

    public Optional<AgentCatalogEntry> disableAgent(String nameOrAlias) {
        return changeStatus(nameOrAlias, "disabled");
    }

    public Optional<AgentCatalogEntry> activateAgent(String nameOrAlias) {
        return changeStatus(nameOrAlias, "active");
    }

    public boolean deleteAgent(String nameOrAlias) {
        return resolve(nameOrAlias)
                .map(entry -> store.delete(entry.agentId()))
                .orElse(false);
    }

    private Optional<AgentCatalogEntry> changeStatus(String nameOrAlias, String status) {
        return resolve(nameOrAlias).map(existing -> store.save(new AgentCatalogEntry(
                existing.agentId(),
                existing.code(),
                existing.displayName(),
                existing.description(),
                existing.systemPrompt(),
                existing.aliases(),
                existing.allowedTools(),
                existing.allowedSkills(),
                existing.modelConfig(),
                existing.memoryScope(),
                status,
                existing.visibility(),
                existing.version() + 1,
                existing.createdAt(),
                Instant.now()
        )));
    }
}
