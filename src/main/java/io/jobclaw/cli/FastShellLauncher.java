package io.jobclaw.cli;

import io.jobclaw.JobClawApplication;
import io.jobclaw.config.Config;
import io.jobclaw.config.ConfigLoader;
import io.jobclaw.run.RunRecord;
import io.jobclaw.run.RunService;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class FastShellLauncher {
    private static final String VERSION = CliCommand.VERSION;
    private static final String ORANGE = "\033[38;5;209m";
    private static final String DIM = "\033[2m";
    private static final String RESET = "\033[0m";
    private static final DateTimeFormatter RUN_TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private FastShellLauncher() {
    }

    public static int run(String[] args) {
        System.setProperty("jobclaw.fast-shell", "true");
        ShellState state = initialState();
        ExecutorService runtimeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "jobclaw-runtime-loader");
            thread.setDaemon(true);
            thread.setContextClassLoader(FastShellLauncher.class.getClassLoader());
            return thread;
        });
        CompletableFuture<ConfigurableApplicationContext> runtime =
                CompletableFuture.supplyAsync(() -> startRuntime(args), runtimeExecutor);

        TerminalBuilder terminalBuilder = TerminalBuilder.builder().name("jobclaw").system(true);
        if (System.console() == null) {
            terminalBuilder.dumb(true);
        }
        try (Terminal terminal = terminalBuilder.build()) {
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .appName("jobclaw")
                    .completer(new StringsCompleter(List.of(
                            "/status", "/runs", "/attach", "/logs", "/artifacts", "/resume", "/help", "/exit", "/quit"
                    )))
                    .variable(LineReader.HISTORY_FILE, historyFile())
                    .build();

            renderHome(terminal, state.withStatus("loading"));
            ConfigurableApplicationContext context = waitForRuntime(terminal, runtime, state);
            if (context == null) {
                return 1;
            }

            Config config = context.getBean(Config.class);
            state = state.withModel(config.getAgent().getModel()).withStatus("ready");
            renderHome(terminal, state);
            try {
                return inputLoop(reader, terminal, context);
            } finally {
                context.close();
            }
        } catch (Exception e) {
            System.err.println("Error starting shell: " + e.getMessage());
            return 1;
        } finally {
            runtimeExecutor.shutdownNow();
            System.clearProperty("jobclaw.fast-shell");
        }
    }

    private static ConfigurableApplicationContext startRuntime(String[] args) {
        return JobClawApplication.cliApplication().run(args);
    }

    private static ConfigurableApplicationContext waitForRuntime(
            Terminal terminal,
            CompletableFuture<ConfigurableApplicationContext> runtime,
            ShellState state
    ) throws Exception {
        String[] frames = {"-", "\\", "|", "/"};
        int i = 0;
        while (!runtime.isDone()) {
            if (System.console() != null) {
                terminal.writer().print("\r" + dim("runtime " + frames[i++ % frames.length] + " loading..."));
                terminal.flush();
            }
            TimeUnit.MILLISECONDS.sleep(120);
        }
        if (System.console() != null) {
            terminal.writer().print("\r" + " ".repeat(40) + "\r");
            terminal.flush();
        }

        try {
            return runtime.get();
        } catch (Exception e) {
            renderHome(terminal, state.withStatus("failed"));
            terminal.writer().println("Runtime failed to load: " + rootMessage(e));
            terminal.flush();
            return null;
        }
    }

    private static int inputLoop(LineReader reader, Terminal terminal, ConfigurableApplicationContext context) throws Exception {
        RunCommand runCommand = context.getBean(RunCommand.class);
        RunService runService = context.getBean(RunService.class);
        ShellSession session = new ShellSession(null);

        while (true) {
            String input;
            try {
                printInputTop(terminal);
                input = reader.readLine("│ > ").trim();
                printInputBottom(terminal);
            } catch (UserInterruptException e) {
                terminal.writer().println();
                terminal.writer().println(dim("Interrupted"));
                terminal.flush();
                return 130;
            } catch (EndOfFileException e) {
                terminal.writer().println();
                return 0;
            }

            if (input.isBlank()) {
                continue;
            }
            if (input.startsWith("/")) {
                if (handleSlash(input, reader, terminal, runService, session)) {
                    return 0;
                }
                continue;
            }

            runCommand.executeTask(input, false, false, session.sessionKey());
            terminal.writer().println();
            terminal.flush();
        }
    }

    private static boolean handleSlash(String input,
                                       LineReader reader,
                                       Terminal terminal,
                                       RunService runService,
                                       ShellSession session) throws Exception {
        String[] parts = input.split("\\s+", 2);
        String command = parts[0];
        String rest = parts.length > 1 ? parts[1].trim() : "";
        switch (command) {
            case "/exit", "/quit" -> {
                return true;
            }
            case "/help", "?" -> {
                printSection(terminal, "shortcuts");
                terminal.writer().println("/status       show recent runs");
                terminal.writer().println("/runs         show more recent runs");
                terminal.writer().println("/logs <id>    show persisted run events");
                terminal.writer().println("/attach <id>  replay a run");
                terminal.writer().println("/resume       choose a recent conversation");
                terminal.writer().println("/resume <id>  attach to a previous conversation");
                terminal.writer().println("/exit         leave");
                terminal.flush();
                return false;
            }
            case "/status", "/runs" -> {
                printSection(terminal, "recent activity");
                List<RunRecord> runs = runService.listRuns("/status".equals(command) ? 10 : 20);
                if (runs.isEmpty()) {
                    terminal.writer().println("No recent activity");
                } else {
                    for (RunRecord run : runs) {
                        terminal.writer().println(run.getRunId() + "  " + run.getStatus() + "  " + summarize(run.getTask()));
                    }
                }
                terminal.flush();
                return false;
            }
            case "/logs" -> {
                printSection(terminal, "logs");
                if (rest.isBlank()) {
                    terminal.writer().println("usage: /logs <runId>");
                } else {
                    runService.readEvents(rest, 80).forEach(event ->
                            terminal.writer().println(event.getTimestamp() + " " + event.getType() + " " + summarize(event.getContent())));
                }
                terminal.flush();
                return false;
            }
            case "/artifacts" -> {
                printSection(terminal, "artifacts");
                if (rest.isBlank()) {
                    terminal.writer().println("usage: /artifacts <runId>");
                } else {
                    List<String> artifacts = runService.readArtifacts(rest);
                    if (artifacts.isEmpty()) {
                        terminal.writer().println("(no artifacts indexed)");
                    } else {
                        artifacts.forEach(path -> terminal.writer().println(path));
                    }
                }
                terminal.flush();
                return false;
            }
            case "/attach" -> {
                printSection(terminal, "attach");
                if (rest.isBlank()) {
                    terminal.writer().println("usage: /attach <runId>");
                } else {
                    new AttachCommand(runService).execute(new String[]{rest});
                }
                terminal.flush();
                return false;
            }
            case "/resume" -> {
                resumeConversation(rest, reader, terminal, runService, session);
                return false;
            }
            default -> {
                terminal.writer().println("unknown command: " + command);
                terminal.flush();
                return false;
            }
        }
    }

    private static void resumeConversation(String runId,
                                           LineReader reader,
                                           Terminal terminal,
                                           RunService runService,
                                           ShellSession session) throws Exception {
        RunRecord selected;
        if (runId != null && !runId.isBlank()) {
            selected = runService.getRequired(runId);
        } else {
            List<RunRecord> runs = runService.listRuns(20);
            printSection(terminal, "resume");
            if (runs.isEmpty()) {
                terminal.writer().println("No recent conversations");
                terminal.flush();
                return;
            }
            for (int i = 0; i < runs.size(); i++) {
                terminal.writer().println(formatRunChoice(i + 1, runs.get(i)));
            }
            terminal.flush();
            String choice = reader.readLine("Select conversation > ").trim();
            if (choice.isBlank()) {
                return;
            }
            int index;
            try {
                index = Integer.parseInt(choice);
            } catch (NumberFormatException e) {
                terminal.writer().println("Invalid selection: " + choice);
                terminal.flush();
                return;
            }
            if (index < 1 || index > runs.size()) {
                terminal.writer().println("Invalid selection: " + choice);
                terminal.flush();
                return;
            }
            selected = runs.get(index - 1);
        }

        session.setSessionKey(selected.getSessionKey());
        printSection(terminal, "conversation");
        terminal.writer().println("attached " + selected.getRunId() + "  " + selected.getStatus());
        terminal.writer().println("session  " + selected.getSessionKey());
        terminal.writer().println("task     " + summarize(selected.getTask()));
        if (selected.getFinalResponse() != null && !selected.getFinalResponse().isBlank()) {
            terminal.writer().println();
            terminal.writer().println("• " + summarize(selected.getFinalResponse()));
        }
        terminal.flush();
    }

    private static ShellState initialState() {
        Config config = quietLoadConfig();
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return new ShellState(
                "model loading",
                "starting",
                cwd.toString(),
                gitBranch(cwd),
                recentActivity(config)
        );
    }

    private static Config quietLoadConfig() {
        Path configPath = Path.of(ConfigLoader.getConfigPath());
        if (!Files.isRegularFile(configPath)) {
            return Config.defaultConfig();
        }
        PrintStream out = System.out;
        PrintStream err = System.err;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream()));
            System.setErr(new PrintStream(new ByteArrayOutputStream()));
            return ConfigLoader.load(configPath.toString());
        } catch (Exception ignored) {
            return Config.defaultConfig();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private static List<String> recentActivity(Config config) {
        try {
            Path index = Path.of(config.getWorkspacePath(), ".jobclaw", "runs", "index.jsonl");
            if (!Files.isRegularFile(index)) {
                return List.of("No recent activity");
            }
            List<String> lines = Files.readAllLines(index);
            if (lines.isEmpty()) {
                return List.of("No recent activity");
            }
            List<String> recent = lines.subList(Math.max(0, lines.size() - 3), lines.size());
            java.util.Collections.reverse(recent);
            return recent.stream()
                    .map(FastShellLauncher::formatRecentRun)
                    .toList();
        } catch (Exception ignored) {
            return List.of("No recent activity");
        }
    }

    private static void renderHome(Terminal terminal, ShellState state) {
        boolean color = System.console() != null;
        int width = Math.max(88, Math.min(terminal.getWidth(), 120));
        int inner = width - 4;
        int left = Math.max(30, inner / 3);
        int right = inner - left - 3;
        String title = " JobClaw Code v" + VERSION + " ";

        if (System.console() != null) {
            terminal.writer().print("\033[2J\033[H");
        }
        terminal.writer().println(top(width, title, color));
        terminal.writer().println(row(left, right, "", colorText("Tips for getting started", color), color));
        terminal.writer().println(row(left, right, center("Welcome back!", left), "Run /help to view shell commands and shortcuts", color));
        terminal.writer().println(row(left, right, "", "Launch tasks from the current project directory", color));
        terminal.writer().println(row(left, right, center("  J O B C L A W", left), repeat("─", right), color));
        terminal.writer().println(row(left, right, center("  \\  |  /", left), colorText("Recent activity", color), color));
        List<String> recent = state.recentActivity();
        terminal.writer().println(row(left, right, center("   \\ | /", left), recent.get(0), color));
        terminal.writer().println(row(left, right, center(" --- * ---", left), recent.size() > 1 ? recent.get(1) : "", color));
        terminal.writer().println(row(left, right, center("   / | \\", left), recent.size() > 2 ? recent.get(2) : "", color));
        terminal.writer().println(row(left, right, "", "", color));
        terminal.writer().println(row(left, right, center(state.model() + " · " + state.status(), left), "", color));
        terminal.writer().println(row(left, right, center(state.branch(), left), "", color));
        terminal.writer().println(row(left, right, center(state.project(), left), "", color));
        terminal.writer().println(bottom(width, color));
        terminal.writer().println();
        terminal.writer().println(dim("? for shortcuts"));
        terminal.writer().println();
        terminal.flush();
    }

    private static String top(int width, String title, boolean color) {
        int right = Math.max(1, width - title.length() - 3);
        return colorText("╭─" + title + repeat("─", right) + "╮", color);
    }

    private static String bottom(int width, boolean color) {
        return colorText("╰" + repeat("─", width - 2) + "╯", color);
    }

    private static String row(int left, int right, String leftText, String rightText, boolean color) {
        return colorText("│", color) + " " + pad(leftText, left)
                + " " + colorText("│", color) + " " + pad(rightText, right) + " " + colorText("│", color);
    }

    private static String colorText(String text, boolean enabled) {
        return enabled ? ORANGE + text + RESET : text;
    }

    private static String dim(String text) {
        return System.console() != null ? DIM + text + RESET : text;
    }

    private static String pad(String value, int width) {
        String clipped = clip(value == null ? "" : value, width);
        return clipped + " ".repeat(Math.max(0, width - visibleLength(clipped)));
    }

    private static String center(String value, int width) {
        String clipped = clip(value == null ? "" : value, width);
        int left = Math.max(0, (width - visibleLength(clipped)) / 2);
        return " ".repeat(left) + clipped;
    }

    private static String clip(String value, int width) {
        if (visibleLength(value) <= width) {
            return value;
        }
        return width <= 3 ? value.substring(0, width) : value.substring(0, width - 3) + "...";
    }

    private static int visibleLength(String value) {
        return value.replaceAll("\\u001B\\[[;\\d]*m", "").length();
    }

    private static String repeat(String value, int count) {
        return value.repeat(Math.max(0, count));
    }

    private static void printInputTop(Terminal terminal) {
        int width = Math.max(40, Math.min(terminal.getWidth(), 160));
        terminal.writer().println("╞" + "═".repeat(width - 2) + "╡");
        terminal.flush();
    }

    private static void printInputBottom(Terminal terminal) {
        int width = Math.max(40, Math.min(terminal.getWidth(), 160));
        terminal.writer().println();
        terminal.writer().println("╘" + "═".repeat(width - 2) + "╛");
        terminal.flush();
    }

    private static void printSection(Terminal terminal, String name) {
        terminal.writer().println();
        terminal.writer().println("----- " + name + " " + "-".repeat(Math.max(1, 62 - name.length())));
    }

    private static Path historyFile() throws Exception {
        Path path = Path.of(System.getProperty("user.home"), ".jobclaw", "history", "shell.history");
        Files.createDirectories(path.getParent());
        return path;
    }

    private static String gitBranch(Path cwd) {
        try {
            Process process = new ProcessBuilder("git", "branch", "--show-current")
                    .directory(cwd.toFile())
                    .start();
            if (!process.waitFor(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return "no-git";
            }
            String branch = new String(process.getInputStream().readAllBytes()).trim();
            return branch.isBlank() ? "no-git" : branch;
        } catch (Exception ignored) {
            return "no-git";
        }
    }

    private static String summarize(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replace("\r", " ").replace("\n", " ").trim();
        return compact.length() > 100 ? compact.substring(0, 100) + "..." : compact;
    }

    private static String formatRecentRun(String jsonLine) {
        String runId = jsonField(jsonLine, "runId");
        String status = jsonField(jsonLine, "status");
        String updatedAt = jsonField(jsonLine, "updatedAt");
        String value = (runId.isBlank() ? "run" : runId)
                + (status.isBlank() ? "" : " " + status)
                + (updatedAt.isBlank() ? "" : " " + updatedAt);
        return value.length() > 70 ? value.substring(0, 67) + "..." : value;
    }

    private static String formatRunChoice(int index, RunRecord run) {
        String time = run.getUpdatedAt() != null ? RUN_TIME_FORMAT.format(run.getUpdatedAt()) : "--";
        return "%2d. %s  %-9s  %s".formatted(index, time, run.getStatus(), summarize(run.getTask()));
    }

    private static String jsonField(String json, String field) {
        if (json == null || json.isBlank()) {
            return "";
        }
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            return "";
        }
        return json.substring(start, end);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    private record ShellState(String model, String status, String project, String branch, List<String> recentActivity) {
        ShellState withStatus(String status) {
            return new ShellState(model, status, project, branch, recentActivity);
        }

        ShellState withModel(String model) {
            return new ShellState(model, status, project, branch, recentActivity);
        }
    }

    private static final class ShellSession {
        private String sessionKey;

        private ShellSession(String sessionKey) {
            this.sessionKey = sessionKey;
        }

        private String sessionKey() {
            return sessionKey;
        }

        private void setSessionKey(String sessionKey) {
            this.sessionKey = sessionKey;
        }
    }
}
