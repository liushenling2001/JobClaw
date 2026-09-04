package io.jobclaw.context;

import io.jobclaw.config.AgentConfig;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.SummaryService;

import java.util.function.IntSupplier;

public class DefaultContextAssemblyPolicy implements ContextAssemblyPolicy {

    private static final int SUMMARY_RETRIEVAL_LIMIT = 4;
    private static final int MEMORY_RETRIEVAL_LIMIT = 8;

    private final AgentConfig agentConfig;
    private final SessionManager sessionManager;
    private final SummaryService summaryService;
    private final IntSupplier contextWindowSupplier;
    private final IntSupplier maxOutputTokensSupplier;

    public DefaultContextAssemblyPolicy(AgentConfig agentConfig,
                                        SessionManager sessionManager,
                                        SummaryService summaryService) {
        this(agentConfig, sessionManager, summaryService, agentConfig::getContextWindow, () -> 0);
    }

    public DefaultContextAssemblyPolicy(AgentConfig agentConfig,
                                        SessionManager sessionManager,
                                        SummaryService summaryService,
                                        IntSupplier contextWindowSupplier) {
        this(agentConfig, sessionManager, summaryService, contextWindowSupplier, () -> 0);
    }

    public DefaultContextAssemblyPolicy(AgentConfig agentConfig,
                                        SessionManager sessionManager,
                                        SummaryService summaryService,
                                        IntSupplier contextWindowSupplier,
                                        IntSupplier maxOutputTokensSupplier) {
        this.agentConfig = agentConfig;
        this.sessionManager = sessionManager;
        this.summaryService = summaryService;
        this.contextWindowSupplier = contextWindowSupplier;
        this.maxOutputTokensSupplier = maxOutputTokensSupplier;
    }

    @Override
    public ContextAssemblyOptions buildOptions(String sessionId, String currentUserInput) {
        int contextWindow = contextWindowSupplier.getAsInt();
        int maxPromptTokens = ContextBudgetPolicy.triggerTokens(
                agentConfig, contextWindow, maxOutputTokensSupplier.getAsInt());
        boolean hasSessionSummary = sessionId != null
                && summaryService.getSessionSummary(sessionId).isPresent();
        int recentTokenBudget = hasSessionSummary
                ? ContextBudgetPolicy.retainTokens(agentConfig, contextWindow)
                : maxPromptTokens;
        int summaryLimit = hasSessionSummary ? SUMMARY_RETRIEVAL_LIMIT : 2;

        return new ContextAssemblyOptions(
                0,
                0,
                summaryLimit,
                MEMORY_RETRIEVAL_LIMIT,
                maxPromptTokens,
                true,
                recentTokenBudget
        );
    }
}
