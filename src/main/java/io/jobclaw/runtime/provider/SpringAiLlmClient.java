package io.jobclaw.runtime.provider;

import io.jobclaw.config.Config;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
                .options(buildChatOptions(resolved.providerName(), resolved.model(), maxTokens, temperature))
                .call()
                .content();
        return response != null ? response : "";
    }

    private ChatModel createChatModel(ResolvedProviderConfig resolved,
                                      Integer maxTokens,
                                      Double temperature) {
        ChatOptions defaultOptions = buildChatOptions(resolved.providerName(), resolved.model(), maxTokens, temperature);
        if (isNativeDeepSeekProvider(resolved.providerName())) {
            DeepSeekChatModel.Builder builder = DeepSeekChatModel.builder()
                    .deepSeekApi(createDeepSeekApi(resolved));
            if (defaultOptions instanceof DeepSeekChatOptions deepSeekOptions) {
                builder.defaultOptions(deepSeekOptions);
            }
            return builder.build();
        }

        OpenAiChatModel.Builder builder = OpenAiChatModel.builder()
                .openAiApi(createOpenAiApi(resolved));
        if (defaultOptions instanceof OpenAiChatOptions openAiOptions) {
            builder.defaultOptions(openAiOptions);
        }
        return builder.build();
    }

    private ChatOptions buildChatOptions(String providerName,
                                         String model,
                                         Integer maxTokens,
                                         Double temperature) {
        if (isNativeDeepSeekProvider(providerName)) {
            DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder().model(model);
            if (maxTokens != null && maxTokens > 0) {
                builder.maxTokens(maxTokens);
            }
            if (temperature != null) {
                builder.temperature(temperature);
            }
            return builder.build();
        }

        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(model);
        if (maxTokens != null && maxTokens > 0) {
            builder.maxTokens(maxTokens);
        }
        if (temperature != null) {
            builder.temperature(temperature);
        }
        return builder.build();
    }

    private DeepSeekApi createDeepSeekApi(ResolvedProviderConfig resolved) {
        return DeepSeekApi.builder()
                .apiKey(resolved.apiKey())
                .baseUrl(resolved.springAiBaseUrl())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory()))
                .build();
    }

    private OpenAiApi createOpenAiApi(ResolvedProviderConfig resolved) {
        return OpenAiApi.builder()
                .apiKey(resolved.apiKey())
                .baseUrl(resolved.springAiBaseUrl())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory()))
                .build();
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        int timeoutMillis = safeTimeoutMillis(config.getAgent().getLlmCallTimeoutSeconds(), 300);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        return requestFactory;
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
