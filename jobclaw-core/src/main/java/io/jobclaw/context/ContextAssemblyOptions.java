package io.jobclaw.context;

public record ContextAssemblyOptions(
        int recentMessageLimit,
        int retrievedHistoryLimit,
        int retrievedSummaryLimit,
        int retrievedMemoryLimit,
        int maxPromptTokens,
        boolean isolateExecutionState
) {

    public ContextAssemblyOptions(int recentMessageLimit,
                                  int retrievedHistoryLimit,
                                  int retrievedSummaryLimit,
                                  int retrievedMemoryLimit,
                                  int maxPromptTokens) {
        this(recentMessageLimit, retrievedHistoryLimit, retrievedSummaryLimit, retrievedMemoryLimit, maxPromptTokens, true);
    }

    public static ContextAssemblyOptions defaults() {
        return new ContextAssemblyOptions(16, 0, 4, 8, 32_768, true);
    }
}
