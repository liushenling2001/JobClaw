package io.jobclaw.context;

public record ContextAssemblyOptions(
        int recentMessageLimit,
        int retrievedHistoryLimit,
        int retrievedSummaryLimit,
        int retrievedMemoryLimit,
        int maxPromptTokens
) {

    public static ContextAssemblyOptions defaults() {
        return new ContextAssemblyOptions(16, 6, 4, 8, 32_768);
    }
}
