package io.jobclaw.agent.evolution;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Scope comes from the execution session, never from model-supplied paths. */
public record MemoryScope(String workspace, String agentId) {
    public MemoryScope {
        workspace = normalizeWorkspace(workspace);
        agentId = agentId == null || agentId.isBlank() ? "default" : agentId;
    }

    public static String normalizeWorkspace(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = Path.of(value).toAbsolutePath().normalize().toString();
        return java.io.File.separatorChar == '\\' ? normalized.toLowerCase(Locale.ROOT) : normalized;
    }

    public boolean permits(String scope, String ownerWorkspace, String ownerAgent) {
        if ("user".equals(scope)) {
            return true;
        }
        if (!"project".equals(scope) && !"agent".equals(scope)) {
            return false;
        }
        try {
            if (workspace == null || !Objects.equals(workspace, normalizeWorkspace(ownerWorkspace))) {
                return false;
            }
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
        return "project".equals(scope) || ("agent".equals(scope) && Objects.equals(agentId, ownerAgent));
    }
}
