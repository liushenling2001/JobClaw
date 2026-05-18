package io.jobclaw.providers;

import io.jobclaw.config.Config;
import io.jobclaw.runtime.provider.ProviderRuntime;
import io.jobclaw.runtime.provider.ResolvedProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LLMProvider adapter backed by Spring AI 2.
 */
public class SpringAiLLMProvider implements LLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiLLMProvider.class);

    private final Config config;
    private final ProviderRuntime providerRuntime;

    public SpringAiLLMProvider(Config config, ProviderRuntime providerRuntime) {
        this.config = config;
        this.providerRuntime = providerRuntime != null ? providerRuntime : new ProviderRuntime();
    }

    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, String model, LLMOptions options) {
        try {
            ResolvedProviderConfig resolved = providerRuntime.resolve(config, model);
            ChatClient chatClient = ChatClient.builder(createChatModel(resolved)).build();
            OpenAiChatOptions.Builder requestOptions = buildRequestOptions(resolved, options);
            String content = chatClient.prompt()
                    .messages(toSpringMessages(messages, shouldNormalizeForDeepSeek(resolved)))
                    .options(requestOptions)
                    .call()
                    .content();
            LLMResponse response = new LLMResponse(content != null ? content : "");
            response.setModel(resolved.model());
            return response;
        } catch (Exception e) {
            logger.error("Spring AI chat request failed", e);
            return new LLMResponse("Error: " + e.getMessage());
        }
    }

    @Override
    public LLMResponse chatStream(List<Message> messages,
                                  List<ToolDefinition> tools,
                                  String model,
                                  LLMOptions options,
                                  StreamCallback callback) {
        try {
            ResolvedProviderConfig resolved = providerRuntime.resolve(config, model);
            ChatClient chatClient = ChatClient.builder(createChatModel(resolved)).build();
            OpenAiChatOptions.Builder requestOptions = buildRequestOptions(resolved, options);
            Duration timeout = requestTimeout();
            List<String> chunks = chatClient.prompt()
                    .messages(toSpringMessages(messages, shouldNormalizeForDeepSeek(resolved)))
                    .options(requestOptions)
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        if (callback != null) {
                            callback.onToken(token);
                        }
                    })
                    .collectList()
                    .block(timeout);
            String content = chunks == null ? "" : String.join("", chunks);
            LLMResponse response = new LLMResponse(content);
            response.setModel(resolved.model());
            if (callback != null) {
                callback.onComplete(response);
            }
            return response;
        } catch (Exception e) {
            logger.error("Spring AI streaming chat request failed", e);
            LLMResponse response = new LLMResponse("Error: " + e.getMessage());
            if (callback != null) {
                callback.onError(e);
            }
            return response;
        }
    }

    private OpenAiChatModel createChatModel(ResolvedProviderConfig resolved) {
        return OpenAiChatModel.builder()
                .options(buildDefaultOptions(resolved))
                .build();
    }

    private OpenAiChatOptions buildDefaultOptions(ResolvedProviderConfig resolved) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        builder.apiKey(resolved.apiKey());
        builder.baseUrl(resolved.springAiBaseUrl());
        builder.model(resolved.model());
        builder.timeout(requestTimeout());
        return builder.build();
    }

    private Duration requestTimeout() {
        return Duration.ofSeconds(Math.max(1, config.getAgent().getLlmCallTimeoutSeconds()));
    }

    private OpenAiChatOptions.Builder buildRequestOptions(ResolvedProviderConfig resolved, LLMOptions options) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        builder.model(resolved.model());
        if (options != null) {
            if (options.getTemperature() != null) {
                builder.temperature(options.getTemperature());
            }
            if (options.getMaxTokens() != null) {
                builder.maxTokens(options.getMaxTokens());
            }
            if (options.getExtra() != null && !options.getExtra().isEmpty()) {
                builder.extraBody(options.getExtra());
            }
        }
        return builder;
    }

    private boolean shouldNormalizeForDeepSeek(ResolvedProviderConfig resolved) {
        String provider = resolved.providerName() != null ? resolved.providerName().toLowerCase() : "";
        String model = resolved.model() != null ? resolved.model().toLowerCase() : "";
        return provider.contains("deepseek") || model.contains("deepseek");
    }

    private List<org.springframework.ai.chat.messages.Message> toSpringMessages(List<Message> messages,
                                                                                boolean normalizeForDeepSeek) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<org.springframework.ai.chat.messages.Message> converted = new ArrayList<>();
        List<Message> messagesForProvider = normalizeForDeepSeek
                ? DeepSeekMessageProtocolNormalizer.normalize(messages)
                : messages;
        for (Message message : messagesForProvider) {
            if (message == null || message.getRole() == null) {
                continue;
            }
            String content = message.getContent() != null ? message.getContent() : "";
            switch (message.getRole()) {
                case "system" -> converted.add(new SystemMessage(content));
                case "assistant" -> converted.add(new AssistantMessage(content));
                case "tool" -> converted.add(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                message.getToolCallId() != null ? message.getToolCallId() : "tool",
                                message.getToolCallId() != null ? message.getToolCallId() : "tool",
                                content
                        )))
                        .build());
                default -> converted.add(new UserMessage(content));
            }
        }
        return converted;
    }
}
