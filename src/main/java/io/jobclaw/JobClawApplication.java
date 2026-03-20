package io.jobclaw;

import io.jobclaw.cli.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.Map;

/**
 * JobClaw - AI Agent Framework based on Spring Boot 3.3
 *
 * @author leavesfly
 * @version 1.0.0
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class JobClawApplication {

    private static boolean shouldRunWebServer = false;

    public static void main(String[] args) {
        // Check if running CLI command (non-web mode)
        if (args.length > 0) {
            String command = args[0];
            // Commands that don't need web server
            if (command.equals("status") || command.equals("onboard") || command.equals("version") || command.equals("demo")) {
                // Set property to disable web server
                System.setProperty("spring.main.web-application-type", "none");
            }
        }
        SpringApplication.run(JobClawApplication.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner() {
        return args -> {
            if (args.length == 0) {
                printHelp();
                return;
            }

            String command = args[0];
            String[] commandArgs = java.util.Arrays.copyOfRange(args, 1, args.length);

            CliCommand cliCommand = getCommand(command);
            if (cliCommand == null) {
                System.out.println("Unknown command: " + command);
                printHelp();
                System.exit(1);
                return;
            }

            try {
                int exitCode = cliCommand.execute(commandArgs);
                System.exit(exitCode);
            } catch (Exception e) {
                System.err.println("Error executing command: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }
        };
    }

    private CliCommand getCommand(String name) {
        return switch (name) {
            case "onboard" -> new OnboardCommand();
            case "status" -> new StatusCommand();
            case "agent" -> new AgentCommand();
            case "gateway" -> new GatewayCommand();
            case "cron" -> new CronCommand();
            case "skills" -> new SkillsCommand();
            case "mcp" -> new McpCommand();
            case "demo" -> new DemoCommand();
            case "version" -> new VersionCommand();
            default -> null;
        };
    }

    private void printHelp() {
        System.out.println("========================================");
        System.out.println("  JobClaw v1.0.0 - AI Agent Framework");
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

    /**
     * Version command - show version info.
     */
    public static class VersionCommand extends CliCommand {
        @Override
        public String name() { return "version"; }

        @Override
        public String description() { return "Show version"; }

        @Override
        public int execute(String[] args) {
            System.out.println(LOGO + " JobClaw v" + VERSION);
            return 0;
        }

        @Override
        public void printHelp() {
            System.out.println(LOGO + " jobclaw version - Show version info");
            System.out.println();
            System.out.println("Usage: jobclaw version");
        }
    }
}
