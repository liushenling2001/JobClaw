package io.jobclaw.run;

public record RunRequest(
        String task,
        String sessionKey,
        String projectRoot,
        String cwd,
        String source,
        String approvalMode,
        String sandboxMode,
        String resumedFromRunId
) {
}
