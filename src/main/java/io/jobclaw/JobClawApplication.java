package io.jobclaw;

import io.jobclaw.cli.CliCommandRegistry;
import io.jobclaw.cli.CliCommand;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

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

    /**
     * 应用启动后执行 CLI 命令。
     */
    @Bean
    public ApplicationRunner applicationRunner(CliCommandRegistry commandRegistry) {
        return args -> {
            java.util.List<String> commandArgs = args.getNonOptionArgs();

            if (commandArgs.isEmpty()) {
                commandRegistry.printHelp();
                return;
            }

            String command = commandArgs.get(0);
            String[] subArgs = commandArgs.subList(1, commandArgs.size()).toArray(new String[0]);

            CliCommand cliCommand = commandRegistry.getCommand(command);
            if (cliCommand == null) {
                System.out.println("Unknown command: " + command);
                commandRegistry.printHelp();
                System.exit(1);
                return;
            }

            try {
                int exitCode = cliCommand.execute(subArgs);
                System.exit(exitCode);
            } catch (Exception e) {
                System.err.println("Error executing command: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }
        };
    }
}
