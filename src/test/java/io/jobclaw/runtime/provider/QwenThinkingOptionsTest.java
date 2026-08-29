package io.jobclaw.runtime.provider;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QwenThinkingOptionsTest {

    @Test
    void shouldLeaveQwenThinkingUnchangedInAutoMode() {
        Map<String, Object> options = QwenThinkingOptions.extraBody(
                "openrouter",
                "qwen",
                "http://100.113.233.0:8000/v1",
                "auto"
        );

        assertTrue(options.isEmpty());
    }

    @Test
    void shouldAllowQwenThinkingToBeEnabledExplicitly() {
        Map<String, Object> options = QwenThinkingOptions.extraBody(
                "openai",
                "qwen3.8",
                "http://localhost:8000/v1",
                "enabled"
        );

        assertEquals(Map.of("chat_template_kwargs", Map.of("enable_thinking", true)), options);
    }

    @Test
    void shouldSendThinkingTokenBudgetToGenericVllmEndpoint() {
        Map<String, Object> options = QwenThinkingOptions.extraBody(
                "openrouter",
                "qwen",
                "http://100.113.233.0:8000/v1",
                "auto",
                8192
        );

        assertEquals(Map.of("thinking_token_budget", 8192), options);
    }

    @Test
    void shouldSendReasoningEffortToSglangCompatibleEndpoint() {
        Map<String, Object> options = QwenThinkingOptions.extraBody(
                "openrouter",
                "qwen",
                "http://192.168.3.168:8000/v1",
                "auto",
                null,
                "high"
        );

        assertEquals(Map.of("reasoning", Map.of("effort", "high")), options);
    }

    @Test
    void shouldPreferReasoningEffortOverLegacyTokenBudget() {
        Map<String, Object> options = QwenThinkingOptions.extraBody(
                "openrouter",
                "qwen",
                "http://192.168.3.168:8000/v1",
                "auto",
                8192,
                "medium"
        );

        assertEquals(Map.of("reasoning", Map.of("effort", "medium")), options);
    }

    @Test
    void shouldNotSendReasoningEffortInAutoMode() {
        Map<String, Object> options = QwenThinkingOptions.extraBody(
                "openrouter",
                "qwen",
                "http://192.168.3.168:8000/v1",
                "auto",
                null,
                "auto"
        );

        assertTrue(options.isEmpty());
    }

    @Test
    void shouldCombineOfficialOpenRouterModeAndReasoningEffort() {
        Map<String, Object> options = QwenThinkingOptions.extraBody(
                "openrouter",
                "qwen/qwen3.5",
                "https://openrouter.ai/api/v1",
                "enabled",
                null,
                "medium"
        );

        assertEquals(Map.of("reasoning", Map.of("enabled", true, "effort", "medium")), options);
    }

    @Test
    void shouldIgnoreReasoningEffortWhenThinkingIsDisabled() {
        Map<String, Object> options = QwenThinkingOptions.extraBody(
                "openai",
                "qwen",
                "http://192.168.3.168:8000/v1",
                "disabled",
                null,
                "high"
        );

        assertEquals(Map.of("chat_template_kwargs", Map.of("enable_thinking", false)), options);
    }

    @Test
    void shouldNotSendThinkingTokenBudgetWhenThinkingIsDisabled() {
        Map<String, Object> options = QwenThinkingOptions.extraBody(
                "openai",
                "qwen3.8",
                "http://localhost:8000/v1",
                "disabled",
                8192
        );

        assertEquals(Map.of("chat_template_kwargs", Map.of("enable_thinking", false)), options);
    }

    @Test
    void shouldNotSendVllmBudgetToOfficialOpenRouterEndpoint() {
        Map<String, Object> options = QwenThinkingOptions.extraBody(
                "openrouter",
                "qwen/qwen3.5",
                "https://openrouter.ai/api/v1",
                "auto",
                8192
        );

        assertTrue(options.isEmpty());
    }

    @Test
    void shouldUseDashScopeThinkingField() {
        Map<String, Object> options = QwenThinkingOptions.extraBody(
                "dashscope",
                "qwen3.5-plus",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "disabled"
        );

        assertEquals(Map.of("enable_thinking", false), options);
    }

    @Test
    void shouldUseOfficialOpenRouterReasoningField() {
        Map<String, Object> options = QwenThinkingOptions.extraBody(
                "openrouter",
                "qwen/qwen3.5",
                "https://openrouter.ai/api/v1",
                "disabled"
        );

        assertEquals(Map.of("reasoning", Map.of("enabled", false)), options);
    }

    @Test
    void shouldLeaveOtherModelsUnchanged() {
        Map<String, Object> options = QwenThinkingOptions.extraBody(
                "openai",
                "gpt-5",
                "https://api.openai.com/v1",
                "disabled"
        );

        assertTrue(options.isEmpty());
    }
}
