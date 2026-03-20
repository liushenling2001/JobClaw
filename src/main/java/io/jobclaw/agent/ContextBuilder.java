package io.jobclaw.agent;

import io.jobclaw.config.Config;
import io.jobclaw.providers.LLMProvider;
import io.jobclaw.providers.Message;
import io.jobclaw.providers.ToolDefinition;
import io.jobclaw.session.SessionManager;
import io.jobclaw.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ContextBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ContextBuilder.class);

    private final Config config;
    private final SessionManager sessionManager;
    private final ToolRegistry toolRegistry;

    private final Map<String, String> fileContentCache;

    public ContextBuilder(Config config, SessionManager sessionManager, ToolRegistry toolRegistry) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.toolRegistry = toolRegistry;
        this.fileContentCache = new ConcurrentHashMap<>();
    }

    public List<Message> buildMessages(String sessionKey, String userContent) {
        List<Message> messages = new ArrayList<>();

        String systemPrompt = buildSystemPrompt(sessionKey);
        messages.add(Message.system(systemPrompt));

        List<Message> history = sessionManager.getHistory(sessionKey);
        messages.addAll(history);

        messages.add(Message.user(userContent));

        return messages;
    }

    public String buildSystemPrompt(String sessionKey) {
        StringBuilder sb = new StringBuilder();

        sb.append("# JobClaw Agent\n\n");
        sb.append("You are a helpful AI assistant powered by JobClaw framework.\n\n");

        sb.append("## Tools Available\n");
        sb.append(toolRegistry.getSummaries());
        sb.append("\n\n");

        String summary = sessionManager.getSummary(sessionKey);
        if (summary != null && !summary.isEmpty()) {
            sb.append("## Conversation Summary\n");
            sb.append(summary);
            sb.append("\n\n");
        }

        sb.append("## Current Session\n");
        sb.append("Session: ").append(sessionKey).append("\n");
        sb.append("Time: ").append(java.time.Instant.now()).append("\n");

        return sb.toString();
    }

    public void clearCache() {
        fileContentCache.clear();
    }

    public void clearCacheForFile(String path) {
        fileContentCache.remove(path);
    }
}
