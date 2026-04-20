package io.jobclaw.agent.profile;

import io.jobclaw.agent.AgentDefinition;
import io.jobclaw.agent.AgentRole;
import io.jobclaw.agent.catalog.AgentCatalogEntry;
import io.jobclaw.agent.catalog.AgentCatalogService;
import io.jobclaw.config.Config;
import io.jobclaw.config.ProvidersConfig;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AgentProfileService {

    public static final String MAIN_AGENT_ID = "main:assistant";
    private static final String ROLE_PREFIX = "role:";
    private static final String AGENT_PREFIX = "agent:";

    private final Config config;
    private final AgentCatalogService agentCatalogService;

    public AgentProfileService(Config config, AgentCatalogService agentCatalogService) {
        this.config = config;
        this.agentCatalogService = agentCatalogService;
        this.agentCatalogService.ensureRoleAgentsExist(defaultModelConfig());
    }

    public List<AgentProfile> listProfiles() {
        List<AgentProfile> profiles = new ArrayList<>();
        profiles.add(mainProfile());
        agentCatalogService.listAgents().stream()
                .map(this::toStoredProfile)
                .forEach(profiles::add);
        return List.copyOf(profiles);
    }

    public Optional<AgentProfile> getProfile(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        if (MAIN_AGENT_ID.equals(id) || "assistant".equalsIgnoreCase(id) || "main".equalsIgnoreCase(id)) {
            return Optional.of(mainProfile());
        }
        if (id.startsWith(ROLE_PREFIX)) {
            String roleCode = id.substring(ROLE_PREFIX.length());
            return agentCatalogService.get(roleCode).map(this::toStoredProfile);
        }
        if (id.startsWith(AGENT_PREFIX)) {
            String agentCode = id.substring(AGENT_PREFIX.length());
            return agentCatalogService.get(agentCode).map(this::toStoredProfile);
        }
        return agentCatalogService.get(id).map(this::toStoredProfile);
    }

    public Optional<ResolvedAgentRuntime> resolveRuntime(String role, String agent) {
        if (agent != null && !agent.isBlank()) {
            return agentCatalogService.resolveDefinition(agent.trim())
                    .flatMap(definition -> agentCatalogService.get(agent.trim())
                            .map(entry -> new ResolvedAgentRuntime(toStoredProfile(entry), definition, false)));
        }

        if (role != null && !role.isBlank()) {
            return agentCatalogService.resolveDefinition(role.trim())
                    .flatMap(definition -> agentCatalogService.get(role.trim())
                            .map(entry -> new ResolvedAgentRuntime(toStoredProfile(entry), definition, false)));
        }

        return Optional.of(new ResolvedAgentRuntime(mainProfile(), null, true));
    }

    private AgentProfile mainProfile() {
        Map<String, Object> modelConfig = defaultModelConfig();
        return new AgentProfile(
                MAIN_AGENT_ID,
                "assistant",
                "主智能体",
                AgentProfileKind.MAIN_AGENT,
                "assistant",
                "Main assistant loaded from ~/.jobclaw/config.json and used as the parent runtime for default subtasks.",
                "主智能体的系统提示由运行时上下文构建器生成。",
                null,
                null,
                modelConfig,
                config.getAgent().getWorkspace(),
                "active",
                1,
                "system",
                true,
                configFilePath(),
                agentsDirectoryPath()
        );
    }

    private AgentProfile toStoredProfile(AgentCatalogEntry entry) {
        boolean isRole = isRoleAgent(entry);
        return new AgentProfile(
                (isRole ? ROLE_PREFIX : AGENT_PREFIX) + entry.code(),
                entry.code(),
                entry.displayName(),
                isRole ? AgentProfileKind.ROLE_TEMPLATE : AgentProfileKind.CATALOG_AGENT,
                isRole ? entry.code() : null,
                entry.description(),
                entry.systemPrompt(),
                entry.allowedTools(),
                entry.allowedSkills(),
                effectiveModelConfig(entry.modelConfig()),
                entry.memoryScope(),
                entry.status(),
                entry.version(),
                isRole ? "system" : "user",
                false,
                agentFilePath(entry.code()),
                agentsDirectoryPath()
        );
    }

    private String configFilePath() {
        return Paths.get(System.getProperty("user.home"), ".jobclaw", "config.json").toString();
    }

    private String agentsDirectoryPath() {
        return Paths.get(config.getWorkspacePath(), ".jobclaw", "agents").toString();
    }

    private String agentFilePath(String code) {
        return Path.of(agentsDirectoryPath(), code + ".json").toString();
    }

    private Map<String, Object> defaultModelConfig() {
        Map<String, Object> modelConfig = new LinkedHashMap<>();
        modelConfig.put("provider", config.getAgent().getProvider());
        modelConfig.put("model", config.getAgent().getModel());
        modelConfig.put("temperature", config.getAgent().getTemperature());
        modelConfig.put("maxTokens", config.getAgent().getMaxTokens());
        modelConfig.put("toolCallTimeoutSeconds", config.getAgent().getToolCallTimeoutSeconds());
        modelConfig.put("subtaskTimeoutMs", config.getAgent().getSubtaskTimeoutMs());
        ProvidersConfig.ProviderConfig providerConfig = config.getProviderConfigByName(config.getAgent().getProvider());
        if (providerConfig != null && providerConfig.getApiBase() != null && !providerConfig.getApiBase().isBlank()) {
            modelConfig.put("apiBase", providerConfig.getApiBase());
        }
        return Map.copyOf(modelConfig);
    }

    private Map<String, Object> effectiveModelConfig(Map<String, Object> modelConfig) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (modelConfig != null) {
            merged.putAll(modelConfig);
        }
        defaultModelConfig().forEach(merged::putIfAbsent);
        merged.remove("apiKey");
        return Map.copyOf(merged);
    }

    private boolean isRoleAgent(AgentCatalogEntry entry) {
        return entry != null && entry.code() != null
                && entry.memoryScope() != null
                && entry.memoryScope().startsWith("role:")
                && AgentRole.fromCode(entry.code()) != AgentRole.ASSISTANT;
    }
}
