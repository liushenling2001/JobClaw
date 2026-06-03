package io.jobclaw.runtime.provider;

import io.jobclaw.config.Config;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class SpringAiLlmClient {

    private final Config config;
    private final ProviderRuntime providerRuntime;

    public SpringAiLlmClient(Config config, ProviderRuntime providerRuntime) {
        this.config = config;
        this.providerRuntime = providerRuntime;
    }

    public String complete(String systemPrompt,
                           String userPrompt,
                           Integer maxTokens,
                           Double temperature) {
        ResolvedProviderConfig resolved = providerRuntime.resolve(config, null);
        ChatClient client = ChatClient.builder(createChatModel(resolved, maxTokens, temperature)).build();
        String response = client.prompt()
                .system(systemPrompt != null ? systemPrompt : "")
                .user(userPrompt != null ? userPrompt : "")
                .call()
                .content();
        return response != null ? response : "";
    }

    private ChatModel createChatModel(ResolvedProviderConfig resolved,
                                      Integer maxTokens,
                                      Double temperature) {
        if (isNativeDeepSeekProvider(resolved.providerName())) {
            return DeepSeekChatModel.builder()
                    .deepSeekApi(DeepSeekApi.builder()
                            .apiKey(resolved.apiKey())
                            .baseUrl(nativeDeepSeekBaseUrl(resolved.springAiBaseUrl()))
                            .build())
                    .defaultOptions(createDeepSeekOptions(resolved, maxTokens, temperature))
                    .build();
        }

        return OpenAiChatModel.builder()
                .options(createOpenAiOptions(resolved, maxTokens, temperature))
                .build();
    }

    private DeepSeekChatOptions createDeepSeekOptions(ResolvedProviderConfig resolved,
                                                      Integer maxTokens,
                                                      Double temperature) {
        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder()
                .model(resolved.model());
        if (maxTokens != null && maxTokens > 0) {
            builder.maxTokens(maxTokens);
        }
        if (temperature != null) {
            builder.temperature(temperature);
        }
        return builder.build();
    }

    private OpenAiChatOptions createOpenAiOptions(ResolvedProviderConfig resolved,
                                                  Integer maxTokens,
                                                  Double temperature) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        builder.apiKey(resolved.apiKey());
        builder.baseUrl(resolved.springAiBaseUrl());
        builder.model(resolved.model());
        builder.timeout(Duration.ofMillis(safeTimeoutMillis(config.getAgent().getLlmCallTimeoutSeconds(), 300)));
        if (maxTokens != null && maxTokens > 0) {
            builder.maxTokens(maxTokens);
        }
        if (temperature != null) {
            builder.temperature(temperature);
        }
        return builder.build();
    }

    private String nativeDeepSeekBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.deepseek.com";
        }
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/v1")) {
            return trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed;
    }

    private int safeTimeoutMillis(int seconds, int fallbackSeconds) {
        long effectiveSeconds = seconds > 0 ? seconds : fallbackSeconds;
        long millis = effectiveSeconds * 1_000L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1_000L, millis));
    }

    private boolean isNativeDeepSeekProvider(String providerName) {
        return "deepseek".equalsIgnoreCase(providerName);
    }
}
