package io.jobclaw;

import io.jobclaw.cli.CliCommandRegistry;
import io.jobclaw.cli.CliCommand;
import io.jobclaw.cli.FastShellLauncher;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

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
        if (args.length == 0 || !isServerCommand(args[0])) {
            configureCliMode();
        }
        if (isInteractiveShellCommand(args)) {
            int exitCode = FastShellLauncher.run(args);
            System.exit(exitCode);
            return;
        }
        SpringApplication.run(JobClawApplication.class, args);
    }

    public static SpringApplication cliApplication() {
        configureCliMode();
        SpringApplication app = new SpringApplication(JobClawApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLazyInitialization(true);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        return app;
    }

    private static boolean isInteractiveShellCommand(String[] args) {
        return args.length == 0 || "shell".equals(args[0]);
    }

    private static boolean isServerCommand(String command) {
        return "gateway".equals(command) || "daemon".equals(command) || "web".equals(command);
    }

    private static void configureCliMode() {
        System.setProperty("spring.main.web-application-type", "none");
        System.setProperty("spring.main.lazy-initialization", "true");
        System.setProperty("spring.main.banner-mode", "off");
        System.setProperty("spring.main.log-startup-info", "false");
        System.setProperty("logging.level.root", "ERROR");
        System.setProperty("logging.level.io.jobclaw", "ERROR");
        System.setProperty("logging.level.org.springframework", "ERROR");
        System.setProperty("logging.level.org.springframework.ai", "ERROR");
        if (System.getProperty("spring.profiles.active") == null && System.getenv("SPRING_PROFILES_ACTIVE") == null) {
            System.setProperty("spring.profiles.active", "cli");
        }
        installCliNoiseFilter();
    }

    private static void installCliNoiseFilter() {
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(new CliNoiseFilter(originalErr), true, StandardCharsets.UTF_8));
    }

    private static final class CliNoiseFilter extends OutputStream {
        private final PrintStream delegate;
        private final StringBuilder line = new StringBuilder();

        private CliNoiseFilter(PrintStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            if (b == '\n') {
                flushLine(true);
                return;
            }
            if (b != '\r') {
                line.append((char) b);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            flushLine(false);
            delegate.flush();
        }

        private void flushLine(boolean appendNewline) {
            if (line.isEmpty()) {
                if (appendNewline) {
                    delegate.println();
                }
                return;
            }
            String text = line.toString();
            line.setLength(0);
            if (shouldSuppress(text)) {
                return;
            }
            if (appendNewline) {
                delegate.println(text);
            } else {
                delegate.print(text);
            }
        }

        private boolean shouldSuppress(String text) {
            return text.startsWith("WARNING: A terminally deprecated method in sun.misc.Unsafe")
                    || text.startsWith("WARNING: sun.misc.Unsafe::allocateMemory")
                    || text.startsWith("WARNING: Please consider reporting this to the maintainers of class io.netty")
                    || text.startsWith("WARNING: A restricted method in java.lang.System has been called")
                    || text.startsWith("WARNING: java.lang.System::load has been called by org.sqlite.SQLiteJDBCLoader")
                    || text.startsWith("WARNING: Use --enable-native-access=ALL-UNNAMED")
                    || text.startsWith("WARNING: Restricted methods will be blocked");
        }
    }

    /**
     * 应用启动后执行 CLI 命令。
     */
    @Bean
    public ApplicationRunner applicationRunner(CliCommandRegistry commandRegistry) {
        return args -> {
            java.util.List<String> commandArgs = args.getNonOptionArgs();
            if (Boolean.getBoolean("jobclaw.fast-shell")) {
                return;
            }
            if (args.containsOption("help") || args.containsOption("h")
                    || (!commandArgs.isEmpty() && "help".equals(commandArgs.get(0)))) {
                commandRegistry.printHelp();
                System.exit(0);
                return;
            }

            String command = commandArgs.isEmpty() ? "shell" : commandArgs.get(0);
            String[] subArgs = commandArgs.isEmpty()
                    ? new String[0]
                    : commandArgs.subList(1, commandArgs.size()).toArray(new String[0]);

            CliCommand cliCommand = commandRegistry.getCommand(command);
            if (cliCommand == null) {
                cliCommand = commandRegistry.getCommand("run");
                subArgs = commandArgs.toArray(new String[0]);
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
