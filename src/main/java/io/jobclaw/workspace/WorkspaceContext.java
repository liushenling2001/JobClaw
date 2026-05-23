package io.jobclaw.workspace;

public record WorkspaceContext(
        String projectRoot,
        String cwd,
        String gitRoot,
        String gitBranch,
        boolean dirty
) {
}
