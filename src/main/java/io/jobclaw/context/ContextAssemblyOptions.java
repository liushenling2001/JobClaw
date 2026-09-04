package io.jobclaw.context;

public record ContextAssemblyOptions(
        int recentMessageLimit,
        int retrievedHistoryLimit,
        int retrievedSummaryLimit,
        int retrievedMemoryLimit,
        int maxPromptTokens,
        boolean isolateExecutionState,
        int recentMessageTokenBudget
) {

    public ContextAssemblyOptions(int recentMessageLimit,
                                  int retrievedHistoryLimit,
                                  int retrievedSummaryLimit,
                                  int retrievedMemoryLimit,
                                  int maxPromptTokens) {
        this(recentMessageLimit, retrievedHistoryLimit, retrievedSummaryLimit, retrievedMemoryLimit,
                maxPromptTokens, true, 0);
    }

    public ContextAssemblyOptions(int recentMessageLimit,
                                  int retrievedHistoryLimit,
                                  int retrievedSummaryLimit,
                                  int retrievedMemoryLimit,
                                  int maxPromptTokens,
                                  boolean isolateExecutionState) {
        this(recentMessageLimit, retrievedHistoryLimit, retrievedSummaryLimit, retrievedMemoryLimit,
                maxPromptTokens, isolateExecutionState, 0);
    }

    public static ContextAssemblyOptions defaults() {
        return new ContextAssemblyOptions(0, 0, 4, 8, 102_400, true, 20_480);
    }
}
