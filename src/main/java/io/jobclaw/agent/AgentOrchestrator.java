package io.jobclaw.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coordinates the primary execution paths for the main agent, role-based agents,
 * and explicit AgentDefinition executions.
 */
@Component
public class AgentOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(AgentOrchestrator.class);

    private static final Pattern ROLE_PATTERN = Pattern.compile(
            "(浣滀负|鎵紨|use|as|role)[:锛歕\\s]*(绋嬪簭鍛?|鐮旂┒鍛?|浣滃|瀹℃煡鍛?|瑙勫垝甯?|娴嬭瘯鍛榺coder|researcher|writer|reviewer|planner|tester)",
            Pattern.CASE_INSENSITIVE
    );

    private final AgentRegistry agentRegistry;

    public AgentOrchestrator(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
        logger.info("AgentOrchestrator initialized");
    }

    public String processWithRole(String sessionKey, String userContent, AgentRole role) {
        logger.info("Orchestrator processing with role {} for session {}", role.getDisplayName(), sessionKey);
        return handleSingleAgentWithRole(sessionKey, userContent, role, null);
    }

    public String processWithRole(String sessionKey,
                                  String userContent,
                                  AgentRole role,
                                  Consumer<ExecutionEvent> eventCallback) {
        logger.info("Orchestrator processing with role {} for session {}", role.getDisplayName(), sessionKey);
        return handleSingleAgentWithRole(sessionKey, userContent, role, eventCallback);
    }

    public String processWithDefinition(String sessionKey,
                                        String userContent,
                                        AgentDefinition definition) {
        return processWithDefinition(sessionKey, userContent, definition, null);
    }

    public String processWithDefinition(String sessionKey,
                                        String userContent,
                                        AgentDefinition definition,
                                        Consumer<ExecutionEvent> eventCallback) {
        try {
            AgentLoop agent = agentRegistry.getOrCreateAgent(definition, sessionKey);
            if (eventCallback != null) {
                return agent.processWithDefinition(sessionKey, userContent, definition, eventCallback);
            }
            return agent.processWithDefinition(sessionKey, userContent, definition);
        } catch (Exception e) {
            logger.error("Error in single-agent mode with definition", e);
            if (eventCallback != null) {
                eventCallback.accept(new ExecutionEvent(sessionKey, ExecutionEvent.EventType.ERROR,
                        "Error: " + e.getMessage()));
            }
            return "Error: " + e.getMessage();
        }
    }

    public String process(String sessionKey, String userContent) {
        logger.info("Orchestrator processing request for session {}", sessionKey);

        if (isSubAgent(sessionKey)) {
            logger.debug("Sub-agent detected, forcing single-agent mode to prevent recursion");
            return handleSingleAgentDefault(sessionKey, userContent, null);
        }

        AgentRole specifiedRole = extractSpecifiedRole(userContent);
        if (specifiedRole != null) {
            logger.info("Specified role detected: {}", specifiedRole.getDisplayName());
            return handleSingleAgentWithRole(sessionKey, userContent, specifiedRole, null);
        }

        logger.info("Single-agent mode (Agent can use spawn tool if needed)");
        return handleSingleAgentDefault(sessionKey, userContent, null);
    }

    public String process(String sessionKey, String userContent, Consumer<ExecutionEvent> eventCallback) {
        logger.info("Orchestrator processing request with callback for session {}", sessionKey);

        if (isSubAgent(sessionKey)) {
            logger.debug("Sub-agent detected, forcing single-agent mode to prevent recursion");
            return handleSingleAgentDefault(sessionKey, userContent, eventCallback);
        }

        AgentRole specifiedRole = extractSpecifiedRole(userContent);
        if (specifiedRole != null) {
            logger.info("Specified role detected: {}", specifiedRole.getDisplayName());
            return handleSingleAgentWithRole(sessionKey, userContent, specifiedRole, eventCallback);
        }

        logger.info("Single-agent mode (Agent can use spawn tool if needed)");
        return handleSingleAgentDefault(sessionKey, userContent, eventCallback);
    }

    private boolean isSubAgent(String sessionKey) {
        return sessionKey.startsWith("spawn-") || sessionKey.startsWith("subagent-");
    }

    private AgentRole extractSpecifiedRole(String userContent) {
        if (userContent == null || userContent.isEmpty()) {
            return null;
        }

        Matcher matcher = ROLE_PATTERN.matcher(userContent);
        if (matcher.find()) {
            String roleCode = matcher.group(2).toLowerCase();
            return AgentRole.fromCode(roleCode);
        }
        return null;
    }

    private String handleSingleAgentDefault(String sessionKey,
                                            String userContent,
                                            Consumer<ExecutionEvent> eventCallback) {
        try {
            AgentLoop agent = agentRegistry.getOrCreateAgent(AgentRole.ASSISTANT, sessionKey);
            if (eventCallback != null) {
                return agent.process(sessionKey, userContent, eventCallback);
            }
            return agent.process(sessionKey, userContent);
        } catch (Exception e) {
            logger.error("Error in single-agent mode", e);
            if (eventCallback != null) {
                eventCallback.accept(new ExecutionEvent(sessionKey, ExecutionEvent.EventType.ERROR,
                        "Error: " + e.getMessage()));
            }
            return "Error: " + e.getMessage();
        }
    }

    private String handleSingleAgentWithRole(String sessionKey,
                                             String userContent,
                                             AgentRole role,
                                             Consumer<ExecutionEvent> eventCallback) {
        try {
            AgentLoop agent = agentRegistry.getOrCreateAgent(role, sessionKey);
            if (eventCallback != null) {
                return agent.process(sessionKey, userContent, role, eventCallback);
            }
            return agent.process(sessionKey, userContent, role);
        } catch (Exception e) {
            logger.error("Error in single-agent mode with role", e);
            if (eventCallback != null) {
                eventCallback.accept(new ExecutionEvent(sessionKey, ExecutionEvent.EventType.ERROR,
                        "Error: " + e.getMessage()));
            }
            return "Error: " + e.getMessage();
        }
    }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("AgentOrchestrator Status:\n");
        sb.append("  Mode: single-agent + explicit role/definition routing\n");
        sb.append("  ").append(agentRegistry.getPoolStatus().replace("\n", "\n  "));
        return sb.toString();
    }
}
