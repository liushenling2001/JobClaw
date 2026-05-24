package io.jobclaw.cli;

import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI 命令注册表，管理所有 CLI 命令的 Spring Bean。
 */
@Component
public class CliCommandRegistry {

    private static final Logger logger = LoggerFactory.getLogger(CliCommandRegistry.class);

    private final Map<String, ObjectProvider<? extends CliCommand>> commands = new LinkedHashMap<>();

    /**
     * 构造函数，注入所有 CLI 命令 Bean 并注册。
     */
    public CliCommandRegistry(
            ObjectProvider<OnboardCommand> onboardCommand,
            ObjectProvider<StatusCommand> statusCommand,
            ObjectProvider<AgentCommand> agentCommand,
            ObjectProvider<GatewayCommand> gatewayCommand,
            ObjectProvider<SkillsCommand> skillsCommand,
            ObjectProvider<McpCommand> mcpCommand,
            ObjectProvider<DemoCommand> demoCommand,
            ObjectProvider<VersionCommand> versionCommand,
            ObjectProvider<AgenticCliCommand> agenticCliCommand,
            ObjectProvider<RunCommand> runCommand,
            ObjectProvider<RunsCommand> runsCommand,
            ObjectProvider<LogsCommand> logsCommand,
            ObjectProvider<AttachCommand> attachCommand,
            ObjectProvider<ArtifactsCommand> artifactsCommand,
            ObjectProvider<ResumeCommand> resumeCommand) {

        register("shell", agenticCliCommand);
        register("run", runCommand);
        register("runs", runsCommand);
        register("logs", logsCommand);
        register("attach", attachCommand);
        register("artifacts", artifactsCommand);
        register("resume", resumeCommand);
        register("onboard", onboardCommand);
        register("status", statusCommand);
        register("agent", agentCommand);
        register("gateway", gatewayCommand);
        register("skills", skillsCommand);
        register("mcp", mcpCommand);
        register("demo", demoCommand);
        register("version", versionCommand);

        logger.info("CLI 命令注册完成，共 {} 个命令", commands.size());
    }

    /**
     * 注册单个命令。
     */
    private void register(String name, ObjectProvider<? extends CliCommand> command) {
        commands.put(name, command);
        logger.debug("注册 CLI 命令：{}", name);
    }

    /**
     * 根据名称获取命令。
     */
    public CliCommand getCommand(String name) {
        ObjectProvider<? extends CliCommand> command = commands.get(name);
        return command == null ? null : command.getObject();
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
        System.out.println("  jobclaw                         # interactive agentic CLI");
        System.out.println("  jobclaw \"task\"                  # run a task in current project");
        System.out.println("  java -jar jobclaw.jar [command] [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  run       - Run an agentic task");
        System.out.println("  runs      - List recent task runs");
        System.out.println("  logs      - Show run event logs");
        System.out.println("  attach    - Replay a run's events");
        System.out.println("  artifacts - List run artifacts");
        System.out.println("  resume    - Resume a previous run");
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
