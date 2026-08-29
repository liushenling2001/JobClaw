package io.jobclaw.runtime.provider;

import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps JobClaw's Qwen thinking mode to provider-specific OpenAI-compatible fields.
 */
public final class QwenThinkingOptions {

    public static final String AUTO = "auto";
    public static final String DISABLED = "disabled";
    public static final String ENABLED = "enabled";

    private QwenThinkingOptions() {
    }

    public static String normalizeMode(String mode) {
        String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case DISABLED, "off", "false" -> DISABLED;
            case ENABLED, "on", "true" -> ENABLED;
            default -> AUTO;
        };
    }

    public static String normalizeReasoningEffort(String effort) {
        String normalized = normalized(effort);
        return switch (normalized) {
            case "none", "minimal", "low", "medium", "high", "xhigh", "max" -> normalized;
            default -> AUTO;
        };
    }

    public static Map<String, Object> extraBody(String providerName,
                                                 String modelName,
                                                 String apiBase,
                                                 String configuredMode) {
        return extraBody(providerName, modelName, apiBase, configuredMode, null);
    }

    public static Map<String, Object> extraBody(String providerName,
                                                 String modelName,
                                                 String apiBase,
                                                 String configuredMode,
                                                 Integer thinkingTokenBudget) {
        return extraBody(providerName, modelName, apiBase, configuredMode, thinkingTokenBudget, null);
    }

    public static Map<String, Object> extraBody(String providerName,
                                                 String modelName,
                                                 String apiBase,
                                                 String configuredMode,
                                                 Integer thinkingTokenBudget,
                                                 String configuredReasoningEffort) {
        if (!isQwenModel(modelName)) {
            return Map.of();
        }

        String mode = normalizeMode(configuredMode);
        String reasoningEffort = normalizeReasoningEffort(configuredReasoningEffort);
        String provider = normalized(providerName);
        String baseUrl = normalized(apiBase);
        if (provider.contains("dashscope") || baseUrl.contains("dashscope.aliyuncs.com")) {
            return AUTO.equals(mode) ? Map.of() : Map.of("enable_thinking", ENABLED.equals(mode));
        }
        if (baseUrl.contains("openrouter.ai")) {
            Map<String, Object> reasoning = new LinkedHashMap<>();
            if (!AUTO.equals(mode)) {
                reasoning.put("enabled", ENABLED.equals(mode));
            }
            if (!DISABLED.equals(mode) && !AUTO.equals(reasoningEffort)) {
                reasoning.put("effort", reasoningEffort);
            }
            return reasoning.isEmpty() ? Map.of() : Map.of("reasoning", Map.copyOf(reasoning));
        }
        if (provider.contains("ollama") || baseUrl.contains(":11434")) {
            return Map.of();
        }

        Map<String, Object> options = new LinkedHashMap<>();
        if (!AUTO.equals(mode)) {
            options.put("chat_template_kwargs", Map.of("enable_thinking", ENABLED.equals(mode)));
        }
        if (!DISABLED.equals(mode) && AUTO.equals(reasoningEffort)
                && thinkingTokenBudget != null && thinkingTokenBudget > 0) {
            options.put("thinking_token_budget", thinkingTokenBudget);
        }
        if (!DISABLED.equals(mode) && !AUTO.equals(reasoningEffort)) {
            options.put("reasoning", Map.of("effort", reasoningEffort));
        }
        return Map.copyOf(options);
    }

    static boolean isQwenModel(String modelName) {
        return normalized(modelName).contains("qwen");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
