package io.jobclaw.context;

import io.jobclaw.config.AgentConfig;

/** Shared model-relative token budgets for initial assembly and active tool loops. */
public final class ContextBudgetPolicy {

    public static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    public static final int DEFAULT_TRIGGER_PERCENTAGE = 80;
    public static final int DEFAULT_RETAIN_PERCENTAGE = 16;
    private static final int MIN_TRIGGER_TOKENS = 4_096;

    private ContextBudgetPolicy() {
    }

    public static int triggerTokens(AgentConfig config, int configuredContextWindow, int maxOutputTokens) {
        int contextWindow = configuredContextWindow > 0 ? configuredContextWindow : DEFAULT_CONTEXT_WINDOW;
        int triggerPercentage = percentage(
                config != null ? config.getCompactionTriggerPercentage() : 0,
                DEFAULT_TRIGGER_PERCENTAGE);
        long ratioBudget = (long) contextWindow * triggerPercentage / 100L;
        long safetyMargin = Math.max(2_048L, contextWindow / 50L);
        long capacityBudget = Math.max(MIN_TRIGGER_TOKENS,
                (long) contextWindow - Math.max(0, maxOutputTokens) - safetyMargin);
        return (int) Math.max(MIN_TRIGGER_TOKENS, Math.min(ratioBudget, capacityBudget));
    }

    public static int retainTokens(AgentConfig config, int configuredContextWindow) {
        int contextWindow = configuredContextWindow > 0 ? configuredContextWindow : DEFAULT_CONTEXT_WINDOW;
        int retainPercentage = percentage(
                config != null ? config.getCompactionRetainPercentage() : 0,
                DEFAULT_RETAIN_PERCENTAGE);
        return Math.max(1_024, (int) ((long) contextWindow * retainPercentage / 100L));
    }

    private static int percentage(int configured, int fallback) {
        return configured > 0 && configured <= 100 ? configured : fallback;
    }
}
