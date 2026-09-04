package io.jobclaw.agent;

import io.jobclaw.config.Config;
import io.jobclaw.config.ModelRuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Applies JobClaw context compaction before every Spring AI tool-loop model call. */
final class ContextManagingToolCallingAdvisor extends ToolCallingAdvisor {

    static final String SESSION_ID_CONTEXT_KEY = "jobclaw.context.sessionId";
    static final String RUN_ID_CONTEXT_KEY = "jobclaw.context.runId";

    private static final Logger logger = LoggerFactory.getLogger(ContextManagingToolCallingAdvisor.class);

    private final Config config;
    private final RunTrajectoryCompactor compactor;

    ContextManagingToolCallingAdvisor(Config config,
                                      ToolCallingManager toolCallingManager,
                                      RunTrajectoryCompactor compactor,
                                      org.springframework.ai.model.tool.ToolExecutionEligibilityChecker eligibilityChecker,
                                      int advisorOrder,
                                      boolean conversationHistoryEnabled) {
        super(toolCallingManager, eligibilityChecker, advisorOrder, conversationHistoryEnabled);
        this.config = config;
        this.compactor = compactor;
    }

    static ToolCallingAdvisor.Builder<?> builder(Config config, RunTrajectoryCompactor compactor) {
        return new ContextManagingBuilder(config, compactor);
    }

    @Override
    protected ChatClientRequest doBeforeStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return compactRequest(request);
    }

    @Override
    protected ChatClientRequest doBeforeCall(ChatClientRequest request, CallAdvisorChain chain) {
        return compactRequest(request);
    }

    ChatClientRequest compactRequest(ChatClientRequest request) {
        Prompt prompt = request.prompt();
        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        String model = prompt.getOptions() != null && prompt.getOptions().getModel() != null
                ? prompt.getOptions().getModel()
                : config.getAgent().getModel();
        int contextWindow = ModelRuntimeConfig.contextWindow(config, model);
        int maxOutputTokens = ModelRuntimeConfig.maxTokens(config, model);
        Map<String, Object> context = request.context();
        String sessionId = contextValue(context, SESSION_ID_CONTEXT_KEY, "tool-loop");
        String runId = contextValue(context, RUN_ID_CONTEXT_KEY, "tool-loop");
        String currentUserContent = latestUserContent(messages);
        int beforeSize = messages.size();
        int requestOverheadTokens = estimateRequestOverheadTokens(prompt);

        compactor.compactIfNeeded(messages, sessionId, runId, currentUserContent,
                contextWindow, maxOutputTokens, requestOverheadTokens);

        if (messages.size() != beforeSize) {
            logger.info("tool-loop context managed session={} run={} model={} messagesBefore={} messagesAfter={} contextWindow={} maxOutputTokens={} requestOverheadTokens={}",
                    sessionId, runId, model, beforeSize, messages.size(), contextWindow,
                    maxOutputTokens, requestOverheadTokens);
        }
        return request.mutate()
                .prompt(new Prompt(messages, prompt.getOptions()))
                .build();
    }

    private String latestUserContent(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof UserMessage && message.getText() != null) {
                return message.getText();
            }
        }
        return "";
    }

    private String contextValue(Map<String, Object> context, String key, String fallback) {
        Object value = context != null ? context.get(key) : null;
        return value != null && !value.toString().isBlank() ? value.toString() : fallback;
    }

    private int estimateRequestOverheadTokens(Prompt prompt) {
        int tokens = 64;
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions toolOptions)
                || toolOptions.getToolCallbacks() == null) {
            return tokens;
        }
        for (ToolCallback callback : toolOptions.getToolCallbacks()) {
            ToolDefinition definition = callback.getToolDefinition();
            String serialized = definition.name() + "\n"
                    + definition.description() + "\n"
                    + definition.inputSchema();
            tokens = saturatedAdd(tokens, compactor.estimateTextTokens(serialized) + 24);
        }
        return tokens;
    }

    private int saturatedAdd(int left, int right) {
        long result = (long) left + right;
        return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static final class ContextManagingBuilder extends ToolCallingAdvisor.Builder<ContextManagingBuilder> {
        private final Config config;
        private final RunTrajectoryCompactor compactor;

        private ContextManagingBuilder(Config config, RunTrajectoryCompactor compactor) {
            this.config = config;
            this.compactor = compactor;
        }

        @Override
        public ToolCallingAdvisor build() {
            return new ContextManagingToolCallingAdvisor(
                    config,
                    getToolCallingManager(),
                    compactor,
                    getToolExecutionEligibilityChecker(),
                    getAdvisorOrder(),
                    isConversationHistoryEnabled()
            );
        }
    }
}
