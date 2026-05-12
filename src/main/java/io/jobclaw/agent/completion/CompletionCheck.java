package io.jobclaw.agent.completion;

public record CompletionCheck(
        String id,
        String type,
        String path,
        String manifestId
) {
}
