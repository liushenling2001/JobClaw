package io.jobclaw.tools;

import io.jobclaw.agent.AgentDefinition;
import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.AgentLoop;
import io.jobclaw.agent.AgentRegistry;
import io.jobclaw.agent.AgentRole;
import io.jobclaw.agent.BoardEventSummarizer;
import io.jobclaw.agent.ExecutionEvent;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.agent.catalog.AgentCatalogService;
import io.jobclaw.board.BoardEntry;
import io.jobclaw.board.BoardRecord;
import io.jobclaw.board.SharedBoardService;
import io.jobclaw.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Multi-agent collaboration based on persistent agents or built-in roles.
 */
@Component
public class CollaborateTool {

    private static final Logger logger = LoggerFactory.getLogger(CollaborateTool.class);

    private final AgentRegistry agentRegistry;
    private final AgentCatalogService agentCatalogService;
    private final SharedBoardService sharedBoardService;
    private final ExecutionTraceService executionTraceService;
    private final BoardEventSummarizer boardEventSummarizer;
    private final Config config;

    public CollaborateTool(@Lazy AgentRegistry agentRegistry,
                           AgentCatalogService agentCatalogService,
                           SharedBoardService sharedBoardService,
                           ExecutionTraceService executionTraceService,
                           BoardEventSummarizer boardEventSummarizer,
                           Config config) {
        this.agentRegistry = agentRegistry;
        this.agentCatalogService = agentCatalogService;
        this.sharedBoardService = sharedBoardService;
        this.executionTraceService = executionTraceService;
        this.boardEventSummarizer = boardEventSummarizer;
        this.config = config;
    }

    @Tool(
            name = "collaborate",
            description = "Launch multi-agent collaboration. Supports modes: TEAM (parallel), SEQUENTIAL (step-by-step), DEBATE (pros/cons discussion). Each entry in roles must reference either a built-in role or a persistent agent from agent_catalog. Temporary role agents inherit the main agent's shared base configuration."
    )
    public String collaborate(
            @ToolParam(description = "Collaboration mode: TEAM (parallel), SEQUENTIAL (step-by-step), DEBATE (pros/cons)") String mode,
            @ToolParam(description = "Task or topic for collaboration") String task,
            @ToolParam(description = "Agent roles (JSON array). Each entry must be one of: {role: string}, {name: string} for a built-in role, or {agent: string} for a persistent agent from agent_catalog. Arbitrary prompt-defined temporary agents are not supported.") String roles,
            @ToolParam(description = "Maximum rounds/iterations (default: 1 for TEAM, 3 for DEBATE)") Integer maxRounds,
            @ToolParam(description = "Timeout in milliseconds (default: 60000)") Long timeoutMs
    ) {
        if (mode == null || mode.isEmpty()) {
            return "Error: mode is required (TEAM, SEQUENTIAL, or DEBATE)";
        }
        if (task == null || task.isEmpty()) {
            return "Error: task is required";
        }

        try {
            CollaborationMode collabMode = parseMode(mode);
            List<AgentDefinition> agentDefs = parseRoles(roles);
            int rounds = maxRounds != null ? maxRounds : (collabMode == CollaborationMode.DEBATE ? 3 : 1);
            long timeout = timeoutMs != null ? timeoutMs : config.getAgent().getSubtaskTimeoutMs();
            String collabSessionKey = "collab-" + System.currentTimeMillis();

            logger.info("Starting collaboration: mode={}, agents={}, sessionKey={}",
                    mode, agentDefs.size(), collabSessionKey);

            return switch (collabMode) {
                case TEAM -> executeTeamMode(collabSessionKey, task, agentDefs, timeout);
                case SEQUENTIAL -> executeSequentialMode(collabSessionKey, task, agentDefs);
                case DEBATE -> executeDebateMode(collabSessionKey, task, agentDefs, rounds);
            };
        } catch (ToolGuidanceException e) {
            logger.warn("Collaboration guidance: {}", e.getMessage());
            return e.getMessage();
        } catch (Exception e) {
            logger.error("Collaboration failed", e);
            return "Error: " + e.getMessage();
        }
    }

    private String executeTeamMode(String collabSessionKey,
                                   String task,
                                   List<AgentDefinition> agents,
                                   long timeout) {
        StringBuilder result = new StringBuilder();
        List<String> results = new ArrayList<>();
        BoardRecord board = sharedBoardService.createBoard(collabSessionKey, "Collaboration board for " + collabSessionKey);
        publishBoardCreated(collabSessionKey, board, "TEAM");
        writeBoardEntry(collabSessionKey, board.boardId(),
                "task",
                "Team task",
                task,
                "system",
                "System",
                "team");

        result.append("## Team Collaboration\n\n");
        result.append("**Task**: ").append(task).append("\n\n");
        result.append("**Board ID**: ").append(board.boardId()).append("\n\n");
        result.append("### Members\n\n");
        for (AgentDefinition def : agents) {
            result.append("- **").append(def.getDisplayName()).append("**");
            if (def.getDescription() != null && !def.getDescription().isBlank()) {
                result.append(": ").append(def.getDescription());
            }
            result.append("\n");
        }
        result.append("\n");
        result.append("### Parallel Execution\n\n");

        ExecutorService executor = Executors.newFixedThreadPool(Math.max(2, agents.size()));
        List<Future<String>> futures = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        for (AgentDefinition def : agents) {
            String agentSessionKey = collabSessionKey + "-" + def.getCode();
            futures.add(executor.submit(() -> {
                try {
                    AgentLoop agent = agentRegistry.getOrCreateAgent(def, agentSessionKey);
                    String agentTask = buildTeamAgentTask(task, board.boardId(), def);
                    return agent.processWithDefinition(agentSessionKey, agentTask, def);
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }));
        }

        for (int i = 0; i < futures.size(); i++) {
            try {
                String agentResult = futures.get(i).get(timeout, TimeUnit.MILLISECONDS);
                results.add(agentResult);
                writeBoardEntry(collabSessionKey, board.boardId(),
                        "artifact",
                        agents.get(i).getDisplayName() + " result",
                        agentResult,
                        agents.get(i).getCode(),
                        agents.get(i).getDisplayName(),
                        "team");
                result.append("**").append(agents.get(i).getDisplayName()).append("**:\n")
                        .append(agentResult).append("\n\n");
            } catch (TimeoutException e) {
                writeBoardEntry(collabSessionKey, board.boardId(),
                        "risk",
                        agents.get(i).getDisplayName() + " timeout",
                        "Execution timed out after " + timeout + "ms",
                        agents.get(i).getCode(),
                        agents.get(i).getDisplayName(),
                        "team");
                result.append("**").append(agents.get(i).getDisplayName()).append("**: Timeout\n\n");
            } catch (Exception e) {
                writeBoardEntry(collabSessionKey, board.boardId(),
                        "risk",
                        agents.get(i).getDisplayName() + " error",
                        e.getMessage(),
                        agents.get(i).getCode(),
                        agents.get(i).getDisplayName(),
                        "team");
                result.append("**").append(agents.get(i).getDisplayName()).append("**: Error: ")
                        .append(e.getMessage()).append("\n\n");
            }
        }

        executor.shutdown();

        long elapsed = System.currentTimeMillis() - startTime;
        result.append("### Stats\n\n");
        result.append("- Execution time: ").append(elapsed).append("ms\n");
        result.append("- Agent count: ").append(agents.size()).append("\n\n");

        result.append("### Summary\n\n");
        AgentDefinition summarizerDef = AgentDefinition.fromRole(AgentRole.WRITER);
        String summarizerSessionKey = collabSessionKey + "-summarizer";
        AgentLoop summarizer = agentRegistry.getOrCreateAgent(summarizerDef, summarizerSessionKey);
        String summaryTask = buildBoardSummaryTask(board.boardId(), sharedBoardService.readEntries(board.boardId(), 200));
        String summary = summarizer.processWithDefinition(summarizerSessionKey, summaryTask, summarizerDef);
        writeBoardEntry(collabSessionKey, board.boardId(),
                "summary",
                "Final team summary",
                summary,
                summarizerDef.getCode(),
                summarizerDef.getDisplayName(),
                "team");
        result.append(summary);

        return result.toString();
    }

    private String executeSequentialMode(String collabSessionKey,
                                         String task,
                                         List<AgentDefinition> agents) {
        StringBuilder result = new StringBuilder();
        BoardRecord board = sharedBoardService.createBoard(collabSessionKey, "Sequential board for " + collabSessionKey);
        publishBoardCreated(collabSessionKey, board, "SEQUENTIAL");
        writeBoardEntry(collabSessionKey, board.boardId(),
                "task",
                "Sequential task",
                task,
                "system",
                "System",
                "team");
        result.append("## Sequential Collaboration\n\n");
        result.append("**Task**: ").append(task).append("\n\n");
        result.append("**Board ID**: ").append(board.boardId()).append("\n\n");

        if (agents.isEmpty()) {
            agents = List.of(
                    AgentDefinition.fromRole(AgentRole.PLANNER),
                    AgentDefinition.fromRole(AgentRole.CODER),
                    AgentDefinition.fromRole(AgentRole.REVIEWER)
            );
        }

        result.append("### Flow\n\n");
        String currentContext = task;

        for (int i = 0; i < agents.size(); i++) {
            AgentDefinition def = agents.get(i);
            result.append("**Step ").append(i + 1).append(": ").append(def.getDisplayName()).append("**\n\n");

            try {
                String agentSessionKey = collabSessionKey + "-" + def.getCode() + "-" + i;
                AgentLoop agent = agentRegistry.getOrCreateAgent(def, agentSessionKey);
                String agentTask = buildSequentialAgentTask(task, currentContext, board.boardId(), def, i + 1);
                String agentResult = agent.processWithDefinition(agentSessionKey, agentTask, def);

                result.append(agentResult).append("\n\n");
                writeBoardEntry(collabSessionKey, board.boardId(),
                        "artifact",
                        "Step " + (i + 1) + " result - " + def.getDisplayName(),
                        agentResult,
                        def.getCode(),
                        def.getDisplayName(),
                        "team");
                currentContext = "Previous result:\n" + agentResult + "\n\nContinue the task: " + task;
            } catch (Exception e) {
                result.append("Error: ").append(e.getMessage()).append("\n\n");
                writeBoardEntry(collabSessionKey, board.boardId(),
                        "risk",
                        "Step " + (i + 1) + " failed - " + def.getDisplayName(),
                        e.getMessage(),
                        def.getCode(),
                        def.getDisplayName(),
                        "team");
                break;
            }
        }

        return result.toString();
    }

    private String executeDebateMode(String collabSessionKey,
                                     String topic,
                                     List<AgentDefinition> agents,
                                     int rounds) {
        StringBuilder result = new StringBuilder();
        BoardRecord board = sharedBoardService.createBoard(collabSessionKey, "Debate board for " + collabSessionKey);
        publishBoardCreated(collabSessionKey, board, "DEBATE");
        writeBoardEntry(collabSessionKey, board.boardId(),
                "task",
                "Debate topic",
                topic,
                "system",
                "System",
                "team");
        result.append("## Debate Mode\n\n");
        result.append("**Topic**: ").append(topic).append("\n\n");
        result.append("**Board ID**: ").append(board.boardId()).append("\n\n");

        if (agents.size() < 2) {
            agents = List.of(
                    AgentDefinition.fromRole(AgentRole.RESEARCHER),
                    AgentDefinition.fromRole(AgentRole.REVIEWER)
            );
        }

        AgentDefinition proponent = agents.get(0);
        AgentDefinition opponent = agents.get(1);

        result.append("### Participants\n\n");
        result.append("- **Side A**: ").append(proponent.getDisplayName()).append("\n");
        result.append("- **Side B**: ").append(opponent.getDisplayName()).append("\n\n");

        String proponentArg = "";
        String opponentArg = "";
        String proponentSessionKey = collabSessionKey + "-side-a";
        String opponentSessionKey = collabSessionKey + "-side-b";

        for (int round = 1; round <= rounds; round++) {
            result.append("### Round ").append(round).append("\n\n");

            try {
                AgentLoop proAgent = agentRegistry.getOrCreateAgent(proponent, proponentSessionKey);
                String proTask = topic + "\n\nPresent your position.";
                if (!opponentArg.isEmpty()) {
                    proTask = "Other side:\n" + opponentArg + "\n\nRespond and strengthen your case: " + topic;
                }
                proponentArg = proAgent.processWithDefinition(proponentSessionKey, proTask, proponent);
                result.append("**Side A**:\n").append(proponentArg).append("\n\n");
                writeBoardEntry(collabSessionKey, board.boardId(),
                        "artifact",
                        "Round " + round + " Side A",
                        proponentArg,
                        proponent.getCode(),
                        proponent.getDisplayName(),
                        "team");
            } catch (Exception e) {
                result.append("**Side A**: Error: ").append(e.getMessage()).append("\n\n");
                writeBoardEntry(collabSessionKey, board.boardId(),
                        "risk",
                        "Round " + round + " Side A error",
                        e.getMessage(),
                        proponent.getCode(),
                        proponent.getDisplayName(),
                        "team");
            }

            try {
                AgentLoop oppAgent = agentRegistry.getOrCreateAgent(opponent, opponentSessionKey);
                String oppTask = "Other side:\n" + proponentArg + "\n\nRespond and rebut: " + topic;
                opponentArg = oppAgent.processWithDefinition(opponentSessionKey, oppTask, opponent);
                result.append("**Side B**:\n").append(opponentArg).append("\n\n");
                writeBoardEntry(collabSessionKey, board.boardId(),
                        "artifact",
                        "Round " + round + " Side B",
                        opponentArg,
                        opponent.getCode(),
                        opponent.getDisplayName(),
                        "team");
            } catch (Exception e) {
                result.append("**Side B**: Error: ").append(e.getMessage()).append("\n\n");
                writeBoardEntry(collabSessionKey, board.boardId(),
                        "risk",
                        "Round " + round + " Side B error",
                        e.getMessage(),
                        opponent.getCode(),
                        opponent.getDisplayName(),
                        "team");
            }
        }

        result.append("### Summary\n\n");
        AgentDefinition summarizerDef = AgentDefinition.fromRole(AgentRole.WRITER);
        String summarizerSessionKey = collabSessionKey + "-summarizer";
        AgentLoop summarizer = agentRegistry.getOrCreateAgent(summarizerDef, summarizerSessionKey);
        String summaryTask = buildBoardSummaryTask(board.boardId(), sharedBoardService.readEntries(board.boardId(), 200));
        String summary = summarizer.processWithDefinition(summarizerSessionKey, summaryTask, summarizerDef);
        writeBoardEntry(collabSessionKey, board.boardId(),
                "summary",
                "Final debate summary",
                summary,
                summarizerDef.getCode(),
                summarizerDef.getDisplayName(),
                "team");
        result.append(summary);

        return result.toString();
    }

    private CollaborationMode parseMode(String mode) {
        return switch (mode.toUpperCase()) {
            case "TEAM" -> CollaborationMode.TEAM;
            case "SEQUENTIAL" -> CollaborationMode.SEQUENTIAL;
            case "DEBATE" -> CollaborationMode.DEBATE;
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        };
    }

    private List<AgentDefinition> parseRoles(String rolesJson) {
        if (rolesJson == null || rolesJson.isEmpty()) {
            return List.of(
                    AgentDefinition.fromRole(AgentRole.CODER),
                    AgentDefinition.fromRole(AgentRole.RESEARCHER),
                    AgentDefinition.fromRole(AgentRole.WRITER)
            );
        }

        List<AgentDefinition> defs = new ArrayList<>();

        try {
            String json = rolesJson.trim();
            if (!json.startsWith("[") || !json.endsWith("]")) {
                throw new ToolGuidanceException("Invalid roles input. Use a JSON array like `[{\"role\":\"coder\"}]` or `[{\"agent\":\"jd analyst\"}]`.");
            }

            json = json.substring(1, json.length() - 1);
            if (json.isBlank()) {
                return defs;
            }

            String[] objects = json.split("\\},\\s*\\{");
            for (String obj : objects) {
                AgentDefinition def = parseRoleObject(obj.replace("{", "").replace("}", "").trim());
                if (def != null) {
                    defs.add(def);
                }
            }
        } catch (ToolGuidanceException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolGuidanceException("Invalid roles input. Use only built-in roles or persistent agents. Details: " + e.getMessage());
        }

        return defs.isEmpty()
                ? List.of(AgentDefinition.fromRole(AgentRole.ASSISTANT))
                : defs;
    }

    private AgentDefinition parseRoleObject(String obj) {
        String role = firstNonBlank(extractJsonValue(obj, "role"), extractJsonValue(obj, "name"));
        String persistentAgentName = extractJsonValue(obj, "agent");
        String prompt = extractJsonValue(obj, "prompt");
        String allowedTools = extractJsonValue(obj, "allowed_tools");

        if (prompt != null || allowedTools != null) {
            String requestedName = role != null && !role.isBlank() ? role.trim()
                    : (persistentAgentName != null ? persistentAgentName.trim() : "that agent");
            throw new ToolGuidanceException(
                    "Temporary prompt-defined agents are not supported. If `" + requestedName + "` should be reusable, create it first with `agent_catalog(action='create', ...)`, then call collaborate with `{\\\"agent\\\":\\\"" + requestedName + "\\\"}`. Otherwise use a built-in role such as `{\\\"role\\\":\\\"coder\\\"}`."
            );
        }

        if (persistentAgentName != null && !persistentAgentName.isBlank()) {
            return agentCatalogService.resolveDefinition(persistentAgentName.trim())
                    .orElseThrow(() -> new ToolGuidanceException(
                            "Persistent agent `" + persistentAgentName.trim() + "` was not found. Create it first with `agent_catalog(action='create', name='" + persistentAgentName.trim() + "', ...)`, then retry with `{\\\"agent\\\":\\\"" + persistentAgentName.trim() + "\\\"}`."
                    ));
        }

        if (role == null || role.isBlank()) {
            throw new ToolGuidanceException("Each collaboration entry must specify either `role` or `agent`.");
        }

        AgentRole predefinedRole = AgentRole.fromCode(role.trim().toLowerCase());
        if (predefinedRole == AgentRole.ASSISTANT && !"assistant".equalsIgnoreCase(role.trim())) {
            throw new ToolGuidanceException(
                    "No built-in role or persistent agent named `" + role.trim() + "` was found. If this should be a reusable custom agent, create it with `agent_catalog(action='create', name='" + role.trim() + "', ...)` and then reference it as `{\\\"agent\\\":\\\"" + role.trim() + "\\\"}`. Otherwise use a built-in role like `coder`, `researcher`, `writer`, `reviewer`, `planner`, or `tester`."
            );
        }
        return AgentDefinition.fromRole(predefinedRole);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return (second != null && !second.isBlank()) ? second : null;
    }

    private String extractJsonValue(String obj, String key) {
        int keyIndex = obj.indexOf("\"" + key + "\"");
        if (keyIndex == -1) {
            keyIndex = obj.indexOf(key + ":");
            if (keyIndex == -1) {
                return null;
            }
        } else {
            keyIndex = obj.indexOf(":", keyIndex);
        }

        if (keyIndex == -1) {
            return null;
        }

        int valueStart = keyIndex + 1;
        while (valueStart < obj.length() && (obj.charAt(valueStart) == ' ' || obj.charAt(valueStart) == '"')) {
            valueStart++;
        }

        char endChar = obj.charAt(valueStart - 1) == '"' ? '"' : ',';
        int valueEnd = obj.indexOf(endChar, valueStart);
        if (valueEnd == -1) {
            valueEnd = obj.length();
        }

        return obj.substring(valueStart, valueEnd).replace("\"", "").trim();
    }

    private String buildTeamAgentTask(String task, String boardId, AgentDefinition def) {
        return "Shared board ID: " + boardId + "\n"
                + "You are collaborating in TEAM mode as " + def.getDisplayName() + ".\n"
                + "Use board_read(boardId=\"" + boardId + "\", limit=20) to inspect shared context if needed.\n"
                + "When you produce findings, decisions, or artifacts, write them with board_write(boardId=\"" + boardId + "\", ...).\n\n"
                + "Task:\n" + task + "\n\n"
                + "Complete this task from your professional perspective.";
    }

    private String buildSequentialAgentTask(String task,
                                            String currentContext,
                                            String boardId,
                                            AgentDefinition def,
                                            int stepNumber) {
        return "Shared board ID: " + boardId + "\n"
                + "You are step " + stepNumber + " in SEQUENTIAL mode as " + def.getDisplayName() + ".\n"
                + "Use board_read(boardId=\"" + boardId + "\", limit=20) if you need prior artifacts.\n"
                + "Write major outputs to the board via board_write(boardId=\"" + boardId + "\", ...).\n\n"
                + "Original task:\n" + task + "\n\n"
                + "Current context:\n" + currentContext + "\n\n"
                + "Complete your portion of the task.";
    }

    private String buildBoardSummaryTask(String boardId, List<BoardEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("Shared board ID: ").append(boardId).append("\n");
        sb.append("Synthesize the collaboration board into one clear final answer.\n\n");
        sb.append("Board entries:\n");
        for (BoardEntry entry : entries) {
            sb.append("- [").append(entry.entryType()).append("] ")
                    .append(entry.title())
                    .append(" | author=").append(entry.authorAgentName())
                    .append("\n")
                    .append(entry.content())
                    .append("\n\n");
        }
        return sb.toString();
    }

    private void publishBoardCreated(String collabSessionKey, BoardRecord board, String mode) {
        executionTraceService.publish(new ExecutionEvent(
                resolveEventSessionId(collabSessionKey),
                ExecutionEvent.EventType.CUSTOM,
                "Shared board created for " + mode + ": " + board.boardId(),
                Map.of(
                        "boardId", board.boardId(),
                        "boardTitle", board.title(),
                        "mode", mode
                )
        ));
    }

    private void writeBoardEntry(String collabSessionKey,
                                 String boardId,
                                 String entryType,
                                 String title,
                                 String content,
                                 String authorAgentId,
                                 String authorAgentName,
                                 String visibility) {
        BoardEntry entry = sharedBoardService.writeEntry(
                boardId,
                entryType,
                title,
                content,
                authorAgentId,
                authorAgentName,
                visibility
        );
        ExecutionEvent event = boardEventSummarizer.toProgressEvent(resolveEventSessionId(collabSessionKey), entry)
                .withMetadata("authorAgentId", entry.authorAgentId())
                .withMetadata("authorAgentName", entry.authorAgentName());
        executionTraceService.publish(event);
    }

    private String resolveEventSessionId(String collabSessionKey) {
        AgentExecutionContext.ExecutionScope scope = AgentExecutionContext.getCurrentScope();
        if (scope != null && scope.sessionKey() != null && !scope.sessionKey().isBlank()) {
            return scope.sessionKey();
        }
        return collabSessionKey;
    }

    private enum CollaborationMode {
        TEAM,
        SEQUENTIAL,
        DEBATE
    }

    private static final class ToolGuidanceException extends RuntimeException {
        private ToolGuidanceException(String message) {
            super(message);
        }
    }
}
