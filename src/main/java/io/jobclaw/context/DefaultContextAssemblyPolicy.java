package io.jobclaw.context;

import io.jobclaw.config.AgentConfig;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.SummaryService;

public class DefaultContextAssemblyPolicy implements ContextAssemblyPolicy {

    private static final int MIN_PROMPT_BUDGET = 2048;
    private static final int MIN_CONTEXT_WINDOW = 4096;
    private static final int MAX_PERCENTAGE = 100;
    private static final int MIN_RETRIEVAL_LIMIT = 1;
    private static final int MAX_RETRIEVAL_LIMIT = 32;

    private final AgentConfig agentConfig;
    private final SessionManager sessionManager;
    private final SummaryService summaryService;

    public DefaultContextAssemblyPolicy(AgentConfig agentConfig,
                                        SessionManager sessionManager,
                                        SummaryService summaryService) {
        this.agentConfig = agentConfig;
        this.sessionManager = sessionManager;
        this.summaryService = summaryService;
    }

    @Override
    public ContextAssemblyOptions buildOptions(String sessionId, String currentUserInput) {
        int contextWindow = Math.max(MIN_CONTEXT_WINDOW, agentConfig.getContextWindow());
        int maxPromptTokens = Math.max(
                MIN_PROMPT_BUDGET,
                contextWindow * normalizePercentage(agentConfig.getContextMaxPromptTokenPercentage()) / 100
        );
        int recentLimit = deriveRecentLimit(contextWindow);
        int memoryLimit = deriveMemoryLimit();
        int maxSummaryRetrieval = normalizeRetrievalLimit(agentConfig.getContextMaxSummaryRetrieval());
        int maxHistoryRetrieval = normalizeRetrievalLimit(agentConfig.getContextMaxHistoryRetrieval());
        int maxMemoryRetrieval = normalizeRetrievalLimit(agentConfig.getContextMaxMemoryRetrieval());
        boolean hasSessionSummary = sessionId != null
                && summaryService.getSessionSummary(sessionId).isPresent();
        int summaryLimit = hasSessionSummary ? Math.min(maxSummaryRetrieval, 4) : Math.min(maxSummaryRetrieval, 2);
        int historyLimit = hasSessionSummary ? Math.min(maxHistoryRetrieval, 4) : Math.min(maxHistoryRetrieval, 8);

        if (isLongUserInput(currentUserInput, contextWindow)) {
            recentLimit = Math.max(8, recentLimit / 2);
            historyLimit = Math.max(2, historyLimit / 2);
            summaryLimit = Math.min(maxSummaryRetrieval, summaryLimit + 1);
            maxPromptTokens = Math.max(
                    MIN_PROMPT_BUDGET,
                    contextWindow * normalizePercentage(agentConfig.getContextLongInputPromptTokenPercentage()) / 100
            );
        }

        if (sessionId != null && sessionManager.getHistory(sessionId).size() > agentConfig.getSummarizeMessageThreshold()) {
            summaryLimit = Math.min(maxSummaryRetrieval, summaryLimit + 1);
            historyLimit = Math.max(2, historyLimit - 1);
        }

        return new ContextAssemblyOptions(
                recentLimit,
                historyLimit,
                summaryLimit,
                Math.min(maxMemoryRetrieval, memoryLimit),
                maxPromptTokens
        );
    }

    private int deriveRecentLimit(int contextWindow) {
        int configured = Math.max(8, agentConfig.getRecentMessagesToKeep());
        if (contextWindow >= 120_000) {
            return Math.min(48, configured);
        }
        if (contextWindow >= 64_000) {
            return Math.min(32, configured);
        }
        if (contextWindow >= 16_000) {
            return Math.min(20, configured);
        }
        return Math.min(12, configured);
    }

    private int deriveMemoryLimit() {
        int contextWindow = Math.max(MIN_CONTEXT_WINDOW, agentConfig.getContextWindow());
        int minBudget = Math.max(256, agentConfig.getMemoryMinTokenBudget());
        int maxBudget = Math.max(minBudget, agentConfig.getMemoryMaxTokenBudget());
        int memoryBudget = contextWindow * normalizePercentage(agentConfig.getMemoryTokenBudgetPercentage()) / 100;
        memoryBudget = Math.max(minBudget, memoryBudget);
        memoryBudget = Math.min(maxBudget, memoryBudget);
        if (memoryBudget >= 8192) {
            return 8;
        }
        if (memoryBudget >= 4096) {
            return 6;
        }
        return 4;
    }

    private boolean isLongUserInput(String currentUserInput, int contextWindow) {
        if (currentUserInput == null || currentUserInput.isBlank()) {
            return false;
        }
        int percentage = normalizePercentage(agentConfig.getContextLongInputTokenPercentage());
        return estimateTokens(currentUserInput) > Math.max(256, contextWindow * percentage / 100);
    }

    private int normalizePercentage(int value) {
        return Math.max(1, Math.min(MAX_PERCENTAGE, value));
    }

    private int normalizeRetrievalLimit(int value) {
        return Math.max(MIN_RETRIEVAL_LIMIT, Math.min(MAX_RETRIEVAL_LIMIT, value));
    }

    private int estimateTokens(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int chineseChars = 0;
        int englishChars = 0;
        for (char c : content.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') {
                chineseChars++;
            } else {
                englishChars++;
            }
        }
        return chineseChars + englishChars / 4;
    }
}
