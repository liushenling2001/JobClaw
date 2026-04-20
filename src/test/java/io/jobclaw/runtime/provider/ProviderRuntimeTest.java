package io.jobclaw.runtime.provider;

import io.jobclaw.config.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRuntimeTest {

    private final ProviderRuntime providerRuntime = new ProviderRuntime();

    @Test
    void shouldResolveRequestedProviderConfig() {
        Config config = Config.defaultConfig();
        config.getAgent().setProvider("openai");
        config.getAgent().setModel("gpt-test");
        config.getProviders().getOpenai().setApiKey("sk-openai");
        config.getProviders().getOpenai().setApiBase("https://example.openai.test/v1");

        ResolvedProviderConfig resolved = providerRuntime.resolve(config, null);

        assertEquals("openai", resolved.providerName());
        assertEquals("gpt-test", resolved.model());
        assertEquals("sk-openai", resolved.apiKey());
        assertEquals("https://example.openai.test/v1", resolved.apiBase());
        assertEquals("https://example.openai.test", resolved.springAiBaseUrl());
        assertFalse(resolved.fallbackUsed());
    }

    @Test
    void shouldFallbackToFirstValidProviderWhenRequestedProviderIsInvalid() {
        Config config = Config.defaultConfig();
        config.getAgent().setProvider("missing-provider");
        config.getAgent().setModel("fallback-model");
        config.getProviders().getOpenai().setApiKey("sk-openai");
        config.getProviders().getOpenai().setApiBase("https://api.openai.com/v1");

        ResolvedProviderConfig resolved = providerRuntime.resolve(config, null);

        assertEquals("openai", resolved.providerName());
        assertEquals("fallback-model", resolved.model());
        assertEquals("sk-openai", resolved.apiKey());
        assertTrue(resolved.fallbackUsed());
    }

    @Test
    void shouldUseProviderDefaultApiBaseWhenConfiguredBaseIsBlank() {
        Config config = Config.defaultConfig();
        config.getAgent().setProvider("openai");
        config.getProviders().getOpenai().setApiKey("sk-openai");
        config.getProviders().getOpenai().setApiBase("");

        ResolvedProviderConfig resolved = providerRuntime.resolve(config, null);

        assertEquals("https://api.openai.com/v1", resolved.apiBase());
        assertEquals("https://api.openai.com", resolved.springAiBaseUrl());
    }

    @Test
    void shouldRejectConfiguredRemoteProviderWithoutApiKey() {
        Config config = Config.defaultConfig();
        config.getAgent().setProvider("dashscope");
        config.getProviders().getDashscope().setApiKey("");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> providerRuntime.resolve(config, null));

        assertTrue(error.getMessage().contains("missing apiKey"));
    }

    @Test
    void shouldAllowOllamaWithoutApiKey() {
        Config config = Config.defaultConfig();
        config.getAgent().setProvider("ollama");
        config.getAgent().setModel("qwen-coder");
        config.getProviders().getOllama().setApiKey("");
        config.getProviders().getOllama().setApiBase("http://localhost:11434/v1");

        ResolvedProviderConfig resolved = providerRuntime.resolve(config, null);

        assertEquals("ollama", resolved.providerName());
        assertEquals("", resolved.apiKey());
        assertEquals("qwen-coder", resolved.model());
    }

    @Test
    void shouldUseChildProviderAndModelButApiKeyFromMainProviders() {
        Config config = Config.defaultConfig();
        config.getAgent().setProvider("dashscope");
        config.getAgent().setModel("qwen-main");
        config.getProviders().getOpenai().setApiKey("sk-openai-child");

        ResolvedProviderConfig resolved = providerRuntime.resolve(
                config,
                "openai",
                null,
                "gpt-child"
        );

        assertEquals("openai", resolved.providerName());
        assertEquals("gpt-child", resolved.model());
        assertEquals("sk-openai-child", resolved.apiKey());
    }
}
