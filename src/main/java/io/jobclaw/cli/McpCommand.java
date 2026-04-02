package io.jobclaw.cli;

import io.jobclaw.config.Config;
import io.jobclaw.config.MCPServersConfig;
import org.springframework.stereotype.Component;

@Component
public class McpCommand extends CliCommand {

    @Override
    public String name() {
        return "mcp";
    }

    @Override
    public String description() {
        return "Manage MCP server connections";
    }

    @Override
    public int execute(String[] args) {
        if (args.length < 2) {
            printHelp();
            return 1;
        }

        String subCommand = args[1];
        try {
            return switch (subCommand) {
                case "list" -> {
                    listServers();
                    yield 0;
                }
                case "test" -> executeWithServerName(args, this::testServer);
                case "tools" -> executeWithServerName(args, this::listTools);
                default -> {
                    System.out.println("Unknown subcommand: " + subCommand);
                    printHelp();
                    yield 1;
                }
            };
        } catch (Exception e) {
            System.err.println("Execution failed: " + e.getMessage());
            return 1;
        }
    }

    private int executeWithServerName(String[] args, ServerNameAction action) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: jobclaw mcp " + args[1] + " <server-name>");
            return 1;
        }
        action.run(args[2]);
        return 0;
    }

    @FunctionalInterface
    private interface ServerNameAction {
        void run(String serverName) throws Exception;
    }

    private MCPServersConfig loadMcpConfig() {
        Config config = loadConfig();
        if (config == null) {
            return null;
        }

        MCPServersConfig mcpConfig = config.getMcpServers();
        if (mcpConfig == null || !mcpConfig.isEnabled()) {
            System.err.println("MCP is not enabled");
            return null;
        }
        return mcpConfig;
    }

    private MCPServersConfig.MCPServerConfig findServerConfig(MCPServersConfig mcpConfig, String name) {
        return mcpConfig.getServers().stream()
                .filter(server -> server.getName().equals(name))
                .findFirst()
                .orElseGet(() -> {
                    System.err.println("Server not found: " + name);
                    return null;
                });
    }

    private void listServers() {
        MCPServersConfig mcpConfig = loadMcpConfig();
        if (mcpConfig == null) {
            return;
        }

        if (mcpConfig.getServers().isEmpty()) {
            System.out.println("No MCP server configured");
            return;
        }

        System.out.println("Configured MCP servers\n");
        for (MCPServersConfig.MCPServerConfig server : mcpConfig.getServers()) {
            String status = server.isEnabled() ? "enabled" : "disabled";
            String type = server.isStdio() ? "stdio" : "sse";
            System.out.println("Name: " + server.getName());
            System.out.println("  Status: " + status);
            System.out.println("  Type: " + type);
            System.out.println("  Description: " + server.getDescription());
            if (server.isStdio()) {
                System.out.println("  Command: " + server.getCommand());
                if (server.getArgs() != null && !server.getArgs().isEmpty()) {
                    System.out.println("  Args: " + String.join(" ", server.getArgs()));
                }
            } else {
                System.out.println("  Endpoint: " + server.getEndpoint());
            }
            System.out.println("  Timeout: " + server.getTimeout() + "ms");
            System.out.println();
        }
    }

    private void testServer(String name) {
        System.out.println("Testing MCP server: " + name + "...\n");

        MCPServersConfig mcpConfig = loadMcpConfig();
        if (mcpConfig == null) {
            return;
        }

        MCPServersConfig.MCPServerConfig serverConfig = findServerConfig(mcpConfig, name);
        if (serverConfig == null) {
            return;
        }

        if (!serverConfig.isEnabled()) {
            System.out.println("Warning: server is disabled");
        }

        System.out.println("Test completed");
    }

    private void listTools(String name) {
        MCPServersConfig mcpConfig = loadMcpConfig();
        if (mcpConfig == null) {
            return;
        }
        if (findServerConfig(mcpConfig, name) == null) {
            return;
        }
        System.out.println("MCP server '" + name + "' tools\n");
        System.out.println("(MCP tools listing is not implemented yet)");
    }

    @Override
    public void printHelp() {
        System.out.println(LOGO + " jobclaw mcp - manage MCP servers");
        System.out.println();
        System.out.println("Usage: jobclaw mcp <subcommand> [options]");
        System.out.println();
        System.out.println("Subcommands:");
        System.out.println("  list              list configured MCP servers");
        System.out.println("  test <name>       test one server");
        System.out.println("  tools <name>      list tools from one server");
    }
}
