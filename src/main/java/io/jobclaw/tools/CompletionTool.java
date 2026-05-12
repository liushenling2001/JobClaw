package io.jobclaw.tools;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.completion.CompletionContract;
import io.jobclaw.agent.completion.CompletionGateResult;
import io.jobclaw.agent.completion.CompletionRegistry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class CompletionTool {

    private final CompletionRegistry completionRegistry;

    public CompletionTool(CompletionRegistry completionRegistry) {
        this.completionRegistry = completionRegistry;
    }

    @Tool(name = "completion", description = "Register or inspect explicit completion checks for the current run. Actions: register, status, clear. Use only when a skill explicitly requires final-output checks.")
    public String completion(
            @ToolParam(description = "Action: register/status/clear") String action,
            @ToolParam(description = "JSON array of checks for register. Supported types: file_exists, file_non_empty, manifest_done.", required = false) String checks,
            @ToolParam(description = "Recovery instruction returned to the model when final checks fail.", required = false) String onFail,
            @ToolParam(description = "Maximum final-check recovery attempts. Default 2.", required = false) String maxAttempts
    ) {
        String normalizedAction = action == null || action.isBlank() ? "status" : action.trim().toLowerCase();
        String sessionKey = currentSessionKey();
        String runId = currentRunId();
        try {
            return switch (normalizedAction) {
                case "register" -> register(sessionKey, runId, checks, onFail, parseInt(maxAttempts, 2));
                case "status" -> formatStatus(completionRegistry.status(sessionKey, runId));
                case "clear" -> {
                    completionRegistry.clear(sessionKey, runId);
                    yield "Completion contract cleared for current run.";
                }
                default -> "Error: unsupported completion action: " + action;
            };
        } catch (Exception e) {
            return "Error: completion operation failed: " + e.getMessage();
        }
    }

    private String register(String sessionKey, String runId, String checks, String onFail, int maxAttempts) throws Exception {
        CompletionContract contract = completionRegistry.register(sessionKey, runId, checks, onFail, maxAttempts);
        return "Completion contract registered.\n\n"
                + "checks: " + contract.checks().size() + "\n"
                + "maxAttempts: " + contract.maxAttempts() + "\n"
                + "runId: " + runId;
    }

    private String formatStatus(CompletionGateResult result) {
        if (!result.registered()) {
            return "No completion contract registered for current run.";
        }
        if (result.passed()) {
            return "Completion checks passed.";
        }
        return result.toModelMessage();
    }

    private String currentSessionKey() {
        String sessionKey = AgentExecutionContext.getCurrentSessionKey();
        return sessionKey == null || sessionKey.isBlank() ? "no-session" : sessionKey;
    }

    private String currentRunId() {
        String runId = AgentExecutionContext.getCurrentRunId();
        return runId == null || runId.isBlank() ? "no-run" : runId;
    }

    private int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
