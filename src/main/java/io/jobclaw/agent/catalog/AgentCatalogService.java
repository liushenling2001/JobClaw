package io.jobclaw.agent.catalog;

import io.jobclaw.agent.AgentDefinition;

import java.time.Instant;
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
        return resolve(nameOrAlias).map(existing -> {
            Instant now = Instant.now();
            AgentCatalogEntry updated = new AgentCatalogEntry(
                    existing.agentId(),
                    existing.code(),
                    existing.displayName(),
                    description != null && !description.isBlank() ? description : existing.description(),
                    systemPrompt != null && !systemPrompt.isBlank() ? systemPrompt : existing.systemPrompt(),
                    aliases != null && !aliases.isEmpty() ? aliases : existing.aliases(),
                    allowedTools != null && !allowedTools.isEmpty() ? allowedTools : existing.allowedTools(),
                    existing.allowedSkills(),
                    existing.modelConfig(),
                    existing.memoryScope(),
                    existing.status(),
                    existing.visibility(),
                    existing.version() + 1,
                    existing.createdAt(),
                    now
            );
            return store.save(updated);
        });
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
