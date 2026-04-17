package io.jobclaw.runtime.tool;

public record ToolExecutionResult(
        String toolId,
        String response,
        long durationMs,
        boolean success,
        String errorMessage
) {
}
