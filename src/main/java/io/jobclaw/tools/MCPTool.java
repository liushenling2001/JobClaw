package io.jobclaw.tools;

import io.jobclaw.mcp.MCPService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP (Model Context Protocol) 工具
 * 
 * 允许 Agent 连接到外部 MCP 服务器，访问资源和工具
 * 
 * 功能：
 * - 连接/断开 MCP 服务器
 * - 列出可用资源、工具、提示词
 * - 读取资源内容
 * - 调用远程工具
 * - 获取提示词模板
 */
@Component
public class MCPTool {

    private final MCPService mcpService;

    public MCPTool(MCPService mcpService) {
        this.mcpService = mcpService;
    }

    @Tool(name = "mcp", description = "Model Context Protocol - Connect to external MCP servers to access resources, tools, and prompts. Use this when you need to: 1) Connect to an MCP server (e.g., filesystem, database, git), 2) List available resources/tools/prompts, 3) Read resource content, 4) Call remote tools, 5) Get prompt templates. IMPORTANT: MCP servers extend your capabilities with external systems.")
    public String mcp(
        @ToolParam(description = "Operation: connect, disconnect, list_servers, list_resources, list_tools, list_prompts, read_resource, call_tool, get_prompt") String action,
        @ToolParam(description = "Server ID (e.g., 'filesystem', 'database', 'git')") String server_id,
        @ToolParam(description = "Server URL (required for connect)") String url,
        @ToolParam(description = "Resource URI (required for read_resource)") String resource_uri,
        @ToolParam(description = "Tool name (required for call_tool)") String tool_name,
        @ToolParam(description = "Tool arguments as JSON string (optional for call_tool)") String arguments,
        @ToolParam(description = "Prompt name (required for get_prompt)") String prompt_name,
        @ToolParam(description = "Prompt arguments as JSON string (optional for get_prompt)") String prompt_arguments
    ) {
        if (action == null || action.isEmpty()) {
            return "Error: action parameter is required";
        }

        return switch (action.toLowerCase()) {
            case "connect" -> connect(server_id, url);
            case "disconnect" -> disconnect(server_id);
            case "list_servers" -> listServers();
            case "list_resources" -> listResources(server_id);
            case "list_tools" -> listTools(server_id);
            case "list_prompts" -> listPrompts(server_id);
            case "read_resource" -> readResource(server_id, resource_uri);
            case "call_tool" -> callTool(server_id, tool_name, arguments);
            case "get_prompt" -> getPrompt(server_id, prompt_name, prompt_arguments);
            default -> "Error: Unknown action: " + action + "\n\nAvailable actions: connect, disconnect, list_servers, list_resources, list_tools, list_prompts, read_resource, call_tool, get_prompt";
        };
    }

    private String connect(String serverId, String url) {
        if (serverId == null || serverId.isEmpty()) {
            return "Error: server_id is required for connect";
        }
        if (url == null || url.isEmpty()) {
            return "Error: url is required for connect";
        }

        try {
            MCPService.MCPServer server = mcpService.connect(serverId, url);
            
            if (server.isConnected()) {
                StringBuilder result = new StringBuilder("✅ Connected to MCP server: **" + serverId + "**\n\n");
                result.append("- **URL**: `" + url + "`\n");
                result.append("- **Name**: " + server.getName() + "\n");
                result.append("- **Version**: " + server.getVersion() + "\n\n");
                
                if (server.getResources() != null && !server.getResources().isEmpty()) {
                    result.append("📦 **Resources**: " + server.getResources().size() + " available\n");
                }
                if (server.getTools() != null && !server.getTools().isEmpty()) {
                    result.append("🛠️ **Tools**: " + server.getTools().size() + " available\n");
                }
                if (server.getPrompts() != null && !server.getPrompts().isEmpty()) {
                    result.append("💬 **Prompts**: " + server.getPrompts().size() + " available\n");
                }
                
                return result.toString();
            } else {
                return "❌ Failed to connect to MCP server: " + serverId + "\n\nError: " + server.getError();
            }
        } catch (Exception e) {
            return "Error connecting to MCP server: " + e.getMessage();
        }
    }

    private String disconnect(String serverId) {
        if (serverId == null || serverId.isEmpty()) {
            return "Error: server_id is required for disconnect";
        }

        try {
            mcpService.disconnect(serverId);
            return "✅ Disconnected from MCP server: " + serverId;
        } catch (Exception e) {
            return "Error disconnecting: " + e.getMessage();
        }
    }

    private String listServers() {
        List<MCPService.MCPServer> servers = mcpService.getAllServers();
        
        if (servers.isEmpty()) {
            return "No MCP servers connected.\n\nUse `mcp(action='connect', server_id='...', url='...')` to connect.";
        }

        StringBuilder result = new StringBuilder("🔌 **Connected MCP Servers**\n\n");
        for (MCPService.MCPServer server : servers) {
            result.append("- **").append(server.getId()).append("** ");
            result.append("(").append(server.getUrl()).append(") ");
            result.append(server.isConnected() ? "✅" : "❌").append("\n");
        }
        return result.toString();
    }

    private String listResources(String serverId) {
        if (serverId == null || serverId.isEmpty()) {
            return "Error: server_id is required";
        }

        MCPService.MCPServer server = mcpService.getServer(serverId);
        if (server == null) {
            return "Error: Server '" + serverId + "' not found. Use list_servers to see connected servers.";
        }

        if (!server.isConnected()) {
            return "Error: Server '" + serverId + "' is not connected";
        }

        if (server.getResources() == null || server.getResources().isEmpty()) {
            return "No resources available on server: " + serverId;
        }

        StringBuilder result = new StringBuilder("📦 **Resources on ").append(serverId).append("**\n\n");
        for (Map<String, Object> resource : server.getResources()) {
            result.append("- **").append(resource.get("name")).append("**\n");
            result.append("  - URI: `").append(resource.get("uri")).append("`\n");
            if (resource.containsKey("description")) {
                result.append("  - Description: ").append(resource.get("description")).append("\n");
            }
        }
        return result.toString();
    }

    private String listTools(String serverId) {
        if (serverId == null || serverId.isEmpty()) {
            return "Error: server_id is required";
        }

        MCPService.MCPServer server = mcpService.getServer(serverId);
        if (server == null) {
            return "Error: Server '" + serverId + "' not found";
        }

        if (!server.isConnected()) {
            return "Error: Server '" + serverId + "' is not connected";
        }

        if (server.getTools() == null || server.getTools().isEmpty()) {
            return "No tools available on server: " + serverId;
        }

        StringBuilder result = new StringBuilder("🛠️ **Tools on ").append(serverId).append("**\n\n");
        for (Map<String, Object> tool : server.getTools()) {
            result.append("- **").append(tool.get("name")).append("**\n");
            if (tool.containsKey("description")) {
                result.append("  - Description: ").append(tool.get("description")).append("\n");
            }
        }
        return result.toString();
    }

    private String listPrompts(String serverId) {
        if (serverId == null || serverId.isEmpty()) {
            return "Error: server_id is required";
        }

        MCPService.MCPServer server = mcpService.getServer(serverId);
        if (server == null) {
            return "Error: Server '" + serverId + "' not found";
        }

        if (!server.isConnected()) {
            return "Error: Server '" + serverId + "' is not connected";
        }

        if (server.getPrompts() == null || server.getPrompts().isEmpty()) {
            return "No prompts available on server: " + serverId;
        }

        StringBuilder result = new StringBuilder("💬 **Prompts on ").append(serverId).append("**\n\n");
        for (Map<String, Object> prompt : server.getPrompts()) {
            result.append("- **").append(prompt.get("name")).append("**\n");
            if (prompt.containsKey("description")) {
                result.append("  - Description: ").append(prompt.get("description")).append("\n");
            }
        }
        return result.toString();
    }

    private String readResource(String serverId, String resourceUri) {
        if (serverId == null || serverId.isEmpty()) {
            return "Error: server_id is required";
        }
        if (resourceUri == null || resourceUri.isEmpty()) {
            return "Error: resource_uri is required";
        }

        MCPService.MCPServer server = mcpService.getServer(serverId);
        if (server == null) {
            return "Error: Server '" + serverId + "' not found";
        }

        if (!server.isConnected()) {
            return "Error: Server '" + serverId + "' is not connected";
        }

        try {
            String content = mcpService.readResource(server.getUrl(), resourceUri);
            return "📄 **Resource Content** (" + resourceUri + ")\n\n```\n" + content + "\n```";
        } catch (Exception e) {
            return "Error reading resource: " + e.getMessage();
        }
    }

    private String callTool(String serverId, String toolName, String argumentsJson) {
        if (serverId == null || serverId.isEmpty()) {
            return "Error: server_id is required";
        }
        if (toolName == null || toolName.isEmpty()) {
            return "Error: tool_name is required";
        }

        MCPService.MCPServer server = mcpService.getServer(serverId);
        if (server == null) {
            return "Error: Server '" + serverId + "' not found";
        }

        if (!server.isConnected()) {
            return "Error: Server '" + serverId + "' is not connected";
        }

        try {
            Map<String, Object> arguments = argumentsJson != null && !argumentsJson.isEmpty() 
                    ? parseJson(argumentsJson) 
                    : Map.of();
            
            String result = mcpService.callTool(server.getUrl(), toolName, arguments);
            return "🛠️ **Tool Result** (" + toolName + ")\n\n" + result;
        } catch (Exception e) {
            return "Error calling tool: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String getPrompt(String serverId, String promptName, String argumentsJson) {
        if (serverId == null || serverId.isEmpty()) {
            return "Error: server_id is required";
        }
        if (promptName == null || promptName.isEmpty()) {
            return "Error: prompt_name is required";
        }

        MCPService.MCPServer server = mcpService.getServer(serverId);
        if (server == null) {
            return "Error: Server '" + serverId + "' not found";
        }

        if (!server.isConnected()) {
            return "Error: Server '" + serverId + "' is not connected";
        }

        try {
            Map<String, Object> arguments = argumentsJson != null && !argumentsJson.isEmpty() 
                    ? (Map<String, Object>) objectMapper.readValue(argumentsJson, Map.class)
                    : Map.of();
            
            String prompt = mcpService.getPrompt(server.getUrl(), promptName, arguments);
            return "💬 **Prompt** (" + promptName + ")\n\n" + prompt;
        } catch (Exception e) {
            return "Error getting prompt: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Invalid JSON: " + e.getMessage());
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper objectMapper = 
            new com.fasterxml.jackson.databind.ObjectMapper();
}
