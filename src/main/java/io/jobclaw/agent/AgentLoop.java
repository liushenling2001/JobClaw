package io.jobclaw.agent;

import io.jobclaw.bus.MessageBus;
import io.jobclaw.config.Config;
import io.jobclaw.providers.*;
import io.jobclaw.session.Session;
import io.jobclaw.session.SessionManager;
import io.jobclaw.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class AgentLoop {

    private static final Logger logger = LoggerFactory.getLogger(AgentLoop.class);

    private final Config config;
    private final SessionManager sessionManager;
    private final ContextBuilder contextBuilder;
    private final LLMProvider provider;
    private final ToolRegistry toolRegistry;
    private final MessageBus messageBus;

    private final int maxToolIterations;
    private final int maxTokens;
    private final double temperature;
    private final String model;

    public AgentLoop(Config config, SessionManager sessionManager, ContextBuilder contextBuilder,
                     LLMProvider provider, ToolRegistry toolRegistry, MessageBus messageBus) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.contextBuilder = contextBuilder;
        this.provider = provider;
        this.toolRegistry = toolRegistry;
        this.messageBus = messageBus;

        this.maxToolIterations = config.getAgent().getMaxToolIterations();
        this.maxTokens = config.getAgent().getMaxTokens();
        this.temperature = config.getAgent().getTemperature();
        this.model = config.getAgent().getModel();
    }

    public String processDirect(String sessionKey, String userContent) {
        try {
            Session session = sessionManager.getOrCreate(sessionKey);

            List<Message> messages = contextBuilder.buildMessages(sessionKey, userContent);

            LLMResponse response = executeLLM(messages);

            session.addMessage("user", userContent);
            session.addMessage("assistant", response.getContent());
            sessionManager.save(session);

            return response.getContent();

        } catch (Exception e) {
            logger.error("Error processing message", e);
            return "Error: " + e.getMessage();
        }
    }

    public String processWithTools(String sessionKey, String userContent) {
        try {
            Session session = sessionManager.getOrCreate(sessionKey);

            List<Message> messages = contextBuilder.buildMessages(sessionKey, userContent);

            LLMResponse response = executeWithToolLoop(messages, sessionKey);

            session.addMessage("user", userContent);
            session.addMessage("assistant", response.getContent());
            sessionManager.save(session);

            return response.getContent();

        } catch (Exception e) {
            logger.error("Error processing message with tools", e);
            return "Error: " + e.getMessage();
        }
    }

    private LLMResponse executeLLM(List<Message> messages) {
        LLMProvider.LLMOptions options = LLMProvider.LLMOptions.create()
                .withTemperature(temperature)
                .withMaxTokens(maxTokens);

        return provider.chat(messages, null, model, options);
    }

    private LLMResponse executeWithToolLoop(List<Message> messages, String sessionKey) throws Exception {
        for (int i = 0; i < maxToolIterations; i++) {
            LLMProvider.LLMOptions options = LLMProvider.LLMOptions.create()
                    .withTemperature(temperature)
                    .withMaxTokens(maxTokens);

            List<io.jobclaw.providers.ToolDefinition> tools = new ArrayList<>();
            for (Map<String, Object> def : toolRegistry.getDefinitions()) {
                tools.add(fromDefinition(def));
            }

            LLMResponse response = provider.chat(messages, tools, model, options);

            if (!response.hasToolCalls()) {
                return response;
            }

            for (ToolCall toolCall : response.getToolCalls()) {
                String result = executeToolCall(toolCall);

                Message toolMessage = Message.tool(toolCall.getId(), result);
                messages.add(toolMessage);
                sessionManager.addFullMessage(sessionKey, toolMessage);
            }

            messages.add(Message.assistant(response.getContent()));
            if (response.getToolCalls() != null) {
                Message assistantMsg = new Message("assistant", response.getContent());
                assistantMsg.setToolCalls(response.getToolCalls());
                messages.add(assistantMsg);
                sessionManager.addFullMessage(sessionKey, assistantMsg);
            }
        }

        throw new IllegalStateException("Max tool iterations reached");
    }

    private String executeToolCall(ToolCall toolCall) throws Exception {
        String toolName = toolCall.getFunction().getName();
        String argsStr = toolCall.getFunction().getArguments();

        Map<String, Object> args = objectMapper.readValue(argsStr, Map.class);
        return toolRegistry.execute(toolName, args);
    }

    private io.jobclaw.providers.ToolDefinition fromDefinition(Map<String, Object> def) {
        Map<String, Object> fn = (Map<String, Object>) def.get("function");
        return new io.jobclaw.providers.ToolDefinition(
                (String) fn.get("name"),
                (String) fn.get("description"),
                (Map<String, Object>) fn.get("parameters")
        );
    }

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public void setProvider(LLMProvider provider) {
        // Allow dynamic provider injection
    }
}
