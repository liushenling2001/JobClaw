package io.jobclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.userinput.UserInputRequest;
import io.jobclaw.agent.userinput.UserInputRequestRegistry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class UserInputTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final UserInputRequestRegistry registry;

    public UserInputTool(UserInputRequestRegistry registry) {
        this.registry = registry;
    }

    @Tool(name = "user_input", description = "Request explicit user input and pause the current run. Use only when the task cannot safely continue without the user's answer, such as missing scope, credentials, a required decision, or a skill step that explicitly requires feedback.")
    public String userInput(
            @ToolParam(description = "The concise question to ask the user.") String question,
            @ToolParam(description = "Why this answer is required before continuing.", required = false) String reason,
            @ToolParam(description = "What the answer is required for. Examples: scope, credentials, safety, artifact, decision, skill.", required = false) String requiredFor,
            @ToolParam(description = "Optional stable key that helps identify what should resume after the answer.", required = false) String resumeKey,
            @ToolParam(description = "Optional answer choices as a JSON array or comma-separated text.", required = false) String options
    ) {
        UserInputRequest request = registry.request(
                currentSessionKey(),
                currentRunId(),
                question,
                reason,
                requiredFor,
                resumeKey,
                parseOptions(options)
        );
        return "USER_INPUT_REQUIRED\n"
                + "requestId: " + request.requestId() + "\n"
                + "requiredFor: " + request.requiredFor() + "\n"
                + "question: " + request.question() + "\n"
                + "options: " + String.join(" | ", request.options()) + "\n"
                + "reason: " + request.reason();
    }

    private String currentSessionKey() {
        String sessionKey = AgentExecutionContext.getCurrentSessionKey();
        return sessionKey == null || sessionKey.isBlank() ? "no-session" : sessionKey;
    }

    private String currentRunId() {
        String runId = AgentExecutionContext.getCurrentRunId();
        return runId == null || runId.isBlank() ? "no-run" : runId;
    }

    private List<String> parseOptions(String options) {
        if (options == null || options.isBlank()) {
            return List.of();
        }
        String value = options.trim();
        try {
            JsonNode node = OBJECT_MAPPER.readTree(value);
            if (node.isArray()) {
                List<String> parsed = new ArrayList<>();
                for (JsonNode item : node) {
                    String text = item.asText("");
                    if (!text.isBlank()) {
                        parsed.add(text.trim());
                    }
                }
                return parsed;
            }
        } catch (Exception ignored) {
            // Fall back to comma-separated choices.
        }
        List<String> parsed = new ArrayList<>();
        for (String item : value.split(",")) {
            if (!item.isBlank()) {
                parsed.add(item.trim());
            }
        }
        return parsed;
    }
}
