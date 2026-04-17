package io.jobclaw.agent.profile;

import java.util.List;
import java.util.Map;

public record AgentProfile(
        String id,
        String code,
        String displayName,
        AgentProfileKind kind,
        String baseRole,
        String description,
        String systemPrompt,
        List<String> allowedTools,
        List<String> allowedSkills,
        Map<String, Object> modelConfig,
        String memoryScope,
        String status,
        int version,
        String source,
        boolean inheritsMainAssistant,
        String configPath,
        String storagePath
) {
}
