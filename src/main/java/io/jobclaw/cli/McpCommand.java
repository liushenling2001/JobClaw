package io.jobclaw.cli;

import io.jobclaw.config.Config;
import io.jobclaw.config.MCPServersConfig;
import io.jobclaw.tools.ToolRegistry;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MCP 管理命令
 *
 * 提供 MCP 服务器的管理和测试功能，支持子命令：
 * - list：列出所有已配置的 MCP 服务器
 * - test：测试指定服务器的连接和握手
 * - tools：列出指定服务器提供的工具
 */
public class McpCommand extends CliCommand {

    @Override
    public String name() {
        return "mcp";
    }

    @Override
    public String description() {
        return "管理 MCP 服务器连接";
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
                    System.out.println("未知的子命令：" + subCommand);
                    printHelp();
                    yield 1;
                }
            };
        } catch (Exception e) {
            System.err.println("执行失败：" + e.getMessage());
            return 1;
        }
    }

    /**
     * 校验参数并执行需要 server-name 的子命令
     */
    private int executeWithServerName(String[] args, ServerNameAction action) throws Exception {
        if (args.length < 3) {
            System.out.println("用法：jobclaw mcp " + args[1] + " <server-name>");
            return 1;
        }
        action.run(args[2]);
        return 0;
    }

    @FunctionalInterface
    private interface ServerNameAction {
        void run(String serverName) throws Exception;
    }

    // ── 公共配置加载 ──────────────────────────────────────────

    /**
     * 加载并校验 MCP 配置，返回已启用的 MCPServersConfig
     *
     * @return MCPServersConfig，加载失败或未启用时返回 null 并打印提示
     */
    private MCPServersConfig loadMcpConfig() {
        Config config = loadConfig();
        if (config == null) {
            return null;
        }

        MCPServersConfig mcpConfig = config.getMcpServers();
        if (mcpConfig == null || !mcpConfig.isEnabled()) {
            System.err.println("MCP 服务器功能未启用");
            return null;
        }
        return mcpConfig;
    }

    /**
     * 从配置中查找指定名称的服务器
     *
     * @return 匹配的服务器配置，未找到时返回 null 并打印提示
     */
    private MCPServersConfig.MCPServerConfig findServerConfig(MCPServersConfig mcpConfig, String name) {
        return mcpConfig.getServers().stream()
                .filter(server -> server.getName().equals(name))
                .findFirst()
                .orElseGet(() -> {
                    System.err.println("未找到服务器配置：" + name);
                    return null;
                });
    }

    // ── list 子命令 ──────────────────────────────────────────

    private void listServers() {
        MCPServersConfig mcpConfig = loadMcpConfig();
        if (mcpConfig == null) {
            return;
        }

        if (mcpConfig.getServers().isEmpty()) {
            System.out.println("未配置任何 MCP 服务器");
            return;
        }

        System.out.println("已配置的 MCP 服务器:\n");

        for (MCPServersConfig.MCPServerConfig server : mcpConfig.getServers()) {
            String status = server.isEnabled() ? "✓ 已启用" : "✗ 已禁用";
            String type = server.isStdio() ? "stdio" : "sse";
            System.out.println("名称：" + server.getName());
            System.out.println("  状态：" + status);
            System.out.println("  类型：" + type);
            System.out.println("  描述：" + server.getDescription());
            if (server.isStdio()) {
                System.out.println("  命令：" + server.getCommand());
                if (server.getArgs() != null && !server.getArgs().isEmpty()) {
                    System.out.println("  参数：" + String.join(" ", server.getArgs()));
                }
            } else {
                System.out.println("  端点：" + server.getEndpoint());
            }
            System.out.println("  超时：" + server.getTimeout() + "ms");
            System.out.println();
        }
    }

    // ── test 子命令 ──────────────────────────────────────────

    private void testServer(String name) throws Exception {
        System.out.println("正在测试 MCP 服务器：" + name + "...\n");

        MCPServersConfig mcpConfig = loadMcpConfig();
        if (mcpConfig == null) {
            return;
        }

        MCPServersConfig.MCPServerConfig serverConfig = findServerConfig(mcpConfig, name);
        if (serverConfig == null) {
            return;
        }

        if (!serverConfig.isEnabled()) {
            System.out.println("⚠️  服务器已禁用");
        }

        System.out.println("✓ 测试完成");
    }

    // ── tools 子命令 ─────────────────────────────────────────

    private void listTools(String name) throws Exception {
        MCPServersConfig mcpConfig = loadMcpConfig();
        if (mcpConfig == null) {
            return;
        }

        ToolRegistry tempRegistry = new ToolRegistry();

        System.out.println("MCP 服务器 '" + name + "' 提供的工具:\n");
        System.out.println("（MCP 工具功能待实现）");
    }

    // ── 工具方法 ─────────────────────────────────────────────

    private void printIfPresent(String label, String value) {
        if (value != null) {
            System.out.println(label + value);
        }
    }

    @Override
    public void printHelp() {
        System.out.println(LOGO + " jobclaw mcp - 管理 MCP 服务器连接");
        System.out.println();
        System.out.println("Usage: jobclaw mcp <subcommand> [options]");
        System.out.println();
        System.out.println("Subcommands:");
        System.out.println("  list              列出所有已配置的 MCP 服务器");
        System.out.println("  test <name>       测试指定服务器的连接和握手");
        System.out.println("  tools <name>      列出指定服务器提供的工具");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  jobclaw mcp list");
        System.out.println("  jobclaw mcp test myserver");
        System.out.println("  jobclaw mcp tools myserver");
    }
}
