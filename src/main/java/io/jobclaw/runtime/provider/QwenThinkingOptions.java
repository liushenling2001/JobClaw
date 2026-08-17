package io.jobclaw.runtime.provider;

import java.util.Locale;
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

    public static Map<String, Object> extraBody(String providerName,
                                                 String modelName,
                                                 String apiBase,
                                                 String configuredMode) {
        if (!isQwenModel(modelName)) {
            return Map.of();
        }

        String mode = normalizeMode(configuredMode);
        if (AUTO.equals(mode)) {
            return Map.of();
        }
        boolean enabled = ENABLED.equals(mode);

        String provider = normalized(providerName);
        String baseUrl = normalized(apiBase);
        if (provider.contains("dashscope") || baseUrl.contains("dashscope.aliyuncs.com")) {
            return Map.of("enable_thinking", enabled);
        }
        if (baseUrl.contains("openrouter.ai")) {
            return Map.of("reasoning", Map.of("enabled", enabled));
        }
        if (provider.contains("ollama") || baseUrl.contains(":11434")) {
            return Map.of();
        }
        return Map.of("chat_template_kwargs", Map.of("enable_thinking", enabled));
    }

    static boolean isQwenModel(String modelName) {
        return normalized(modelName).contains("qwen");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
