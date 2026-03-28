package io.jobclaw.cli;

import io.jobclaw.SpringContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI 命令注册表，管理所有 CLI 命令的 Spring Bean。
 */
@Component
public class CliCommandRegistry {

    private static final Logger logger = LoggerFactory.getLogger(CliCommandRegistry.class);

    private final Map<String, CliCommand> commands = new HashMap<>();

    /**
     * 构造函数，注入所有 CLI 命令 Bean 并注册。
     */
    public CliCommandRegistry(
            OnboardCommand onboardCommand,
            StatusCommand statusCommand,
            AgentCommand agentCommand,
            GatewayCommand gatewayCommand,
            SkillsCommand skillsCommand,
            McpCommand mcpCommand,
            DemoCommand demoCommand,
            VersionCommand versionCommand) {

        register(onboardCommand);
        register(statusCommand);
        register(agentCommand);
        register(gatewayCommand);
        register(skillsCommand);
        register(mcpCommand);
        register(demoCommand);
        register(versionCommand);

        logger.info("CLI 命令注册完成，共 {} 个命令", commands.size());
    }

    /**
     * 注册单个命令。
     */
    private void register(CliCommand command) {
        commands.put(command.name(), command);
        logger.debug("注册 CLI 命令：{}", command.name());
    }

    /**
     * 根据名称获取命令。
     */
    public CliCommand getCommand(String name) {
        return commands.get(name);
    }

    /**
     * 获取所有已注册的命令名称。
     */
    public List<String> getCommandNames() {
        return List.copyOf(commands.keySet());
    }

    /**
     * 打印所有命令的帮助信息。
     */
    public void printHelp() {
        System.out.println("========================================");
        System.out.println("  JobClaw v" + CliCommand.VERSION + " - AI Agent Framework");
        System.out.println("  Based on Spring Boot 3.3 + Java 17");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar jobclaw.jar [command] [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  onboard   - Initialize configuration and workspace");
        System.out.println("  agent     - Interact with Agent (CLI mode)");
        System.out.println("  gateway   - Start gateway service (all channels)");
        System.out.println("  status    - Show system status");
        System.out.println("  cron      - Manage scheduled tasks");
        System.out.println("  skills    - Manage skills");
        System.out.println("  mcp       - Manage MCP servers");
        System.out.println("  demo      - Run demo");
        System.out.println("  version   - Show version");
        System.out.println("========================================");
    }
}
