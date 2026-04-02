package io.jobclaw.tools;

import io.jobclaw.agent.catalog.AgentCatalogEntry;
import io.jobclaw.agent.catalog.AgentCatalogService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manage persistent specialized agent definitions. Invocation should still go through spawn.
 */
@Component
public class AgentCatalogTool {

    private final AgentCatalogService agentCatalogService;

    public AgentCatalogTool(AgentCatalogService agentCatalogService) {
        this.agentCatalogService = agentCatalogService;
    }

    @Tool(name = "agent_catalog", description = "Manage reusable persistent agents. Actions: create, list, get, update, disable, activate, delete. Use this tool only to store and inspect agent definitions. To execute an existing persistent agent, always use spawn with the agent parameter instead of building a separate execution flow.")
    public String agentCatalog(
            @ToolParam(description = "Action: create, list, get, update, disable, activate, or delete") String action,
            @ToolParam(description = "Agent display name or identifier. Required for create/get/update/disable/activate/delete.") String name,
            @ToolParam(description = "Agent description. Required for create. Optional for update.") String description,
            @ToolParam(description = "Agent system prompt. Optional for create; a default prompt will be generated from the description if omitted.") String systemPrompt,
            @ToolParam(description = "Comma-separated aliases for the agent. Optional for create or update.") String aliases,
            @ToolParam(description = "Comma-separated allowed tools whitelist. Optional for create or update.") String allowedTools
    ) {
        String normalizedAction = action != null ? action.trim().toLowerCase() : "";
        return switch (normalizedAction) {
            case "create" -> create(name, description, systemPrompt, aliases, allowedTools);
            case "list" -> list();
            case "get" -> get(name);
            case "update" -> update(name, description, systemPrompt, aliases, allowedTools);
            case "disable" -> disable(name);
            case "activate" -> activate(name);
            case "delete" -> delete(name);
            default -> "Error: unsupported action. Use create, list, get, update, disable, activate, or delete.";
        };
    }

    private String create(String name,
                          String description,
                          String systemPrompt,
                          String aliases,
                          String allowedTools) {
        if (name == null || name.isBlank()) {
            return "Error: name is required for create.";
        }
        String normalizedDescription = description != null && !description.isBlank()
                ? description.trim()
                : "处理用户指定的专项工作";
        String effectivePrompt = systemPrompt != null && !systemPrompt.isBlank()
                ? systemPrompt.trim()
                : buildDefaultPrompt(name.trim(), normalizedDescription);

        AgentCatalogEntry entry = agentCatalogService.createAgent(
                toCode(name),
                name.trim(),
                normalizedDescription,
                effectivePrompt,
                parseCsv(aliases, name),
                parseCsv(allowedTools),
                List.of(),
                Map.of(),
                "agent:" + toCode(name)
        );

        return "Agent created: `" + entry.displayName() + "`\n\n" +
                "Code: `" + entry.code() + "`\n" +
                "Aliases: " + String.join(", ", entry.aliases()) + "\n" +
                "Invoke with `spawn(agent=\"" + entry.displayName() + "\", task=\"...\")`";
    }

    private String list() {
        List<AgentCatalogEntry> entries = agentCatalogService.listAgents();
        if (entries.isEmpty()) {
            return "No persistent agents found.";
        }
        return entries.stream()
                .map(entry -> "- `" + entry.displayName() + "` (`" + entry.code() + "`): " + entry.description())
                .collect(Collectors.joining("\n"));
    }

    private String get(String name) {
        if (name == null || name.isBlank()) {
            return "Error: name is required for get.";
        }
        return agentCatalogService.resolve(name.trim())
                .map(entry -> "Agent: `" + entry.displayName() + "`\n\n" +
                        "Code: `" + entry.code() + "`\n" +
                        "Description: " + entry.description() + "\n" +
                        "Status: " + entry.status() + "\n" +
                        "Aliases: " + String.join(", ", entry.aliases()) + "\n" +
                        "Allowed tools: " + String.join(", ", entry.allowedTools()))
                .orElse("Agent not found: " + name.trim());
    }

    private String update(String name,
                          String description,
                          String systemPrompt,
                          String aliases,
                          String allowedTools) {
        if (name == null || name.isBlank()) {
            return "Error: name is required for update.";
        }
        return agentCatalogService.updateAgent(
                        name.trim(),
                        description,
                        systemPrompt,
                        parseCsv(aliases),
                        parseCsv(allowedTools)
                )
                .map(entry -> "Agent updated: `" + entry.displayName() + "`\n\n" +
                        "Version: " + entry.version() + "\n" +
                        "Status: " + entry.status())
                .orElse("Agent not found: " + name.trim());
    }

    private String disable(String name) {
        if (name == null || name.isBlank()) {
            return "Error: name is required for disable.";
        }
        return agentCatalogService.disableAgent(name.trim())
                .map(entry -> "Agent disabled: `" + entry.displayName() + "`")
                .orElse("Agent not found: " + name.trim());
    }

    private String activate(String name) {
        if (name == null || name.isBlank()) {
            return "Error: name is required for activate.";
        }
        return agentCatalogService.activateAgent(name.trim())
                .map(entry -> "Agent activated: `" + entry.displayName() + "`")
                .orElse("Agent not found: " + name.trim());
    }

    private String delete(String name) {
        if (name == null || name.isBlank()) {
            return "Error: name is required for delete.";
        }
        return agentCatalogService.deleteAgent(name.trim())
                ? "Agent deleted: `" + name.trim() + "`"
                : "Agent not found: " + name.trim();
    }

    private List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    private List<String> parseCsv(String csv, String fallback) {
        List<String> parsed = parseCsv(csv);
        if (parsed.isEmpty() && fallback != null && !fallback.isBlank()) {
            return List.of(fallback.trim(), fallback.trim() + "智能体");
        }
        return parsed;
    }

    private String buildDefaultPrompt(String name, String description) {
        return "你是 `" + name + "`。\n" +
                "你的专属职责是：" + description + "。\n" +
                "只围绕该职责完成任务，必要时使用允许的工具，避免偏离角色边界。";
    }

    private String toCode(String displayName) {
        String code = displayName.trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "_")
                .replaceAll("^_+|_+$", "");
        return code.isBlank() ? "custom_agent" : code;
    }
}
