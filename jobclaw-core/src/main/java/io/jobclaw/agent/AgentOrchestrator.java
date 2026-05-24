package io.jobclaw.agent;

import io.jobclaw.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direct execution router.
 *
 * Long-task reliability is handled by direct execution hygiene, explicit tools,
 * durable artifacts, and skills.
 */
@Component
public class AgentOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final Pattern ROLE_PATTERN = Pattern.compile(
            "(?:use|as|role|作为|扮演)[:：\\s]*(coder|researcher|writer|reviewer|planner|tester|程序员|研究员|作家|审查员|规划师|测试员)",
            Pattern.CASE_INSENSITIVE
    );

    private final AgentRegistry agentRegistry;

    @Autowired
    public AgentOrchestrator(Config config, AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
        logger.info("AgentOrchestrator initialized in direct mode");
    }

    public String processWithRole(String sessionKey, String userContent, AgentRole role) {
        return processWithRole(sessionKey, userContent, role, null);
    }

    public String processWithRole(String sessionKey,
                                  String userContent,
                                  AgentRole role,
                                  AgentExecutionOptions executionOptions,
                                  Consumer<ExecutionEvent> eventCallback) {
        AgentLoop agent = agentRegistry.getOrCreateAgent(role, sessionKey);
        return agent.process(sessionKey, userContent, role, executionOptions, eventCallback);
    }

    public String processWithRole(String sessionKey,
                                  String userContent,
                                  AgentRole role,
                                  Consumer<ExecutionEvent> eventCallback) {
        AgentLoop agent = agentRegistry.getOrCreateAgent(role, sessionKey);
        return eventCallback != null
                ? agent.process(sessionKey, userContent, role, eventCallback)
                : agent.process(sessionKey, userContent, role);
    }

    public String processWithDefinition(String sessionKey,
                                        String userContent,
                                        AgentDefinition definition) {
        return processWithDefinition(sessionKey, userContent, definition, (Consumer<ExecutionEvent>) null);
    }

    public String processWithDefinition(String sessionKey,
                                        String userContent,
                                        AgentDefinition definition,
                                        AgentExecutionOptions executionOptions,
                                        Consumer<ExecutionEvent> eventCallback) {
        AgentLoop agent = agentRegistry.getOrCreateAgent(definition, sessionKey);
        return agent.processWithDefinition(sessionKey, userContent, definition, executionOptions, eventCallback);
    }

    public String processWithDefinition(String sessionKey,
                                        String userContent,
                                        AgentDefinition definition,
                                        Consumer<ExecutionEvent> eventCallback) {
        AgentLoop agent = agentRegistry.getOrCreateAgent(definition, sessionKey);
        return eventCallback != null
                ? agent.processWithDefinition(sessionKey, userContent, definition, eventCallback)
                : agent.processWithDefinition(sessionKey, userContent, definition);
    }

    public String process(String sessionKey, String userContent) {
        return process(sessionKey, userContent, (Consumer<ExecutionEvent>) null);
    }

    public String process(String sessionKey,
                          String userContent,
                          AgentExecutionOptions executionOptions,
                          Consumer<ExecutionEvent> eventCallback) {
        AgentRole specifiedRole = extractSpecifiedRole(userContent);
        if (specifiedRole != null) {
            return processWithRole(sessionKey, userContent, specifiedRole, executionOptions, eventCallback);
        }
        AgentLoop agent = agentRegistry.getOrCreateAgent(AgentRole.ASSISTANT, sessionKey);
        return agent.process(sessionKey, userContent, executionOptions, eventCallback);
    }

    public String process(String sessionKey, String userContent, Consumer<ExecutionEvent> eventCallback) {
        AgentRole specifiedRole = extractSpecifiedRole(userContent);
        if (specifiedRole != null) {
            return processWithRole(sessionKey, userContent, specifiedRole, eventCallback);
        }
        AgentLoop agent = agentRegistry.getOrCreateAgent(AgentRole.ASSISTANT, sessionKey);
        return eventCallback != null
                ? agent.process(sessionKey, userContent, eventCallback)
                : agent.process(sessionKey, userContent);
    }

    public String getStatus() {
        return "AgentOrchestrator Status:\n"
                + "  Mode: direct\n"
                + "  " + agentRegistry.getPoolStatus().replace("\n", "\n  ");
    }

    private AgentRole extractSpecifiedRole(String userContent) {
        if (userContent == null || userContent.isBlank()) {
            return null;
        }
        Matcher matcher = ROLE_PATTERN.matcher(userContent);
        if (!matcher.find()) {
            return null;
        }
        return switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
            case "coder", "程序员" -> AgentRole.CODER;
            case "researcher", "研究员" -> AgentRole.RESEARCHER;
            case "writer", "作家" -> AgentRole.WRITER;
            case "reviewer", "审查员" -> AgentRole.REVIEWER;
            case "planner", "规划师" -> AgentRole.PLANNER;
            case "tester", "测试员" -> AgentRole.TESTER;
            default -> null;
        };
    }
}
