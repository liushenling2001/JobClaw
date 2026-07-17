package io.jobclaw.cli;

import io.jobclaw.JobClawApplication;
import io.jobclaw.agent.ExecutionEvent;
import io.jobclaw.cli.render.TranscriptTuiRenderer;
import io.jobclaw.cli.tui.FullScreenCli;
import io.jobclaw.config.Config;
import io.jobclaw.config.ConfigLoader;
import io.jobclaw.run.RunRecord;
import io.jobclaw.run.RunRequest;
import io.jobclaw.run.RunService;
import io.jobclaw.skills.SkillInfo;
import io.jobclaw.skills.SkillsService;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Candidate;
import org.jline.reader.Reference;
import org.jline.keymap.KeyMap;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private static final List<ShellCommandHelp> SHELL_COMMANDS = List.of(
            new ShellCommandHelp("/status", "show recent runs"),
            new ShellCommandHelp("/runs", "show more recent runs"),
            new ShellCommandHelp("/logs", "show persisted run events"),
            new ShellCommandHelp("/tools", "list collapsed tool calls"),
            new ShellCommandHelp("/tool", "expand a tool result"),
            new ShellCommandHelp("/artifacts", "list run artifacts"),
            new ShellCommandHelp("/skills", "list available skills"),
            new ShellCommandHelp("/attach", "replay a run"),
            new ShellCommandHelp("/resume", "choose a recent conversation"),
            new ShellCommandHelp("/help", "show shortcuts"),
            new ShellCommandHelp("/exit", "leave JobClaw"),
            new ShellCommandHelp("/quit", "leave JobClaw")
    );
    private static volatile ShellCompletionState activeCompletionState = ShellCompletionState.empty();
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
        if (!isWindows()) {
            terminalBuilder.provider("exec").jna(false).jansi(false).jni(false).ffm(false).exec(true);
        }
        if (System.console() == null) {
            terminalBuilder.dumb(true);
        }
        try (Terminal terminal = terminalBuilder.build()) {
            if (System.console() != null) {
                return new FullScreenCli(terminal, runtime).run();
            }
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .appName("jobclaw")
                    .completer(FastShellLauncher::completeShellInput)
                    .variable(LineReader.HISTORY_FILE, historyFile())
                    .variable(LineReader.LIST_MAX, 100)
                    .variable(LineReader.MENU_LIST_MAX, 20)
                    .build();
            configureCompletion(reader);

            renderHome(terminal, state.withStatus("loading"));
            ConfigurableApplicationContext context = waitForRuntime(terminal, runtime, state);
            if (context == null) {
                return 1;
            }

            Config config = context.getBean(Config.class);
            state = state.withModel(config.getAgent().getModel()).withStatus("ready");
            prewarmNativeLibraries();
            renderHome(terminal, state);
            try {
                SkillsService skillsService = context.getBean(SkillsService.class);
                ShellCompletionState completionState = new ShellCompletionState(skillsService.listSkills());
                activeCompletionState = completionState;
                return inputLoop(reader, terminal, context, completionState);
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

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void prewarmNativeLibraries() {
        try {
            Class<?> loader = Class.forName("org.sqlite.SQLiteJDBCLoader");
            loader.getMethod("initialize").invoke(null);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Best effort: keep native-access warnings in the loading phase when sqlite-jdbc is present.
        }
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

    private static int inputLoop(LineReader reader,
                                 Terminal terminal,
                                 ConfigurableApplicationContext context,
                                 ShellCompletionState completionState) throws Exception {
        RunCommand runCommand = context.getBean(RunCommand.class);
        RunService runService = context.getBean(RunService.class);
        Config config = context.getBean(Config.class);
        ShellSession session = new ShellSession(null);

        while (true) {
            String input;
            try {
                printInputTop(terminal);
                input = reader.readLine("│ > ").trim();
                clearSubmittedInputBox(terminal);
            } catch (UserInterruptException e) {
                clearSubmittedInputBox(terminal);
                if (confirmExit(reader, terminal)) {
                    return 130;
                }
                continue;
            } catch (EndOfFileException e) {
                terminal.writer().println();
                return 0;
            }

            if (input.isBlank()) {
                continue;
            }
            if ("/".equals(input)) {
                printSlashPalette(terminal, completionState);
                continue;
            }
            if (input.startsWith("/") || "?".equals(input)) {
                if (handleSlash(input, reader, terminal, runCommand, runService, session, completionState)) {
                    return 0;
                }
                continue;
            }

            TranscriptTuiRenderer renderer = new TranscriptTuiRenderer(
                    terminal,
                    config.getAgent().getModel(),
                    System.getProperty("user.dir")
            );
            renderer.renderUser(input);
            RunRecord record = runService.startForeground(new RunRequest(
                    input,
                    session.sessionKey(),
                    System.getProperty("user.dir"),
                    System.getProperty("user.dir"),
                    "cli",
                    "ask",
                    "workspace-write",
                    null
            ), renderer::render);
            renderer.finish(record.getRunId());
            session.setLastRunId(record.getRunId());
            session.setLastRenderer(renderer);
            terminal.flush();
        }
    }

    private static boolean handleSlash(String input,
                                       LineReader reader,
                                       Terminal terminal,
                                       RunCommand runCommand,
                                       RunService runService,
                                       ShellSession session,
                                       ShellCompletionState completionState) throws Exception {
        String[] parts = input.split("\\s+", 2);
        String command = parts[0];
        String rest = parts.length > 1 ? parts[1].trim() : "";
        switch (command) {
            case "/exit", "/quit" -> {
                return true;
            }
            case "/help", "?" -> {
                printSection(terminal, "shortcuts");
                terminal.writer().println("/status             show recent runs");
                terminal.writer().println("/runs               show more recent runs");
                terminal.writer().println("/logs <id>          show persisted run events");
                terminal.writer().println("/tools [id]         list collapsed tool calls");
                terminal.writer().println("/tool [id] <n>      expand a tool result");
                terminal.writer().println("/tool <n>           expand a tool result from the last run");
                terminal.writer().println("/skills             list available skills");
                terminal.writer().println("/attach <id>        replay a run");
                terminal.writer().println("/resume             choose a recent conversation");
                terminal.writer().println("/resume <id>        attach to a previous conversation");
                terminal.writer().println("/<skill> <task>     run a task with a specific skill");
                terminal.writer().println("/exit               leave");
                terminal.writer().println();
                terminal.writer().println(dim("Tip: type / and press Tab to open the command palette."));
                terminal.writer().println(dim("Tab cycles commands; Shift+Tab moves backward where the terminal supports it."));
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
            case "/tools" -> {
                listTools(rest, terminal, runService, session);
                return false;
            }
            case "/tool" -> {
                showTool(rest, terminal, runService, session);
                return false;
            }
            case "/skills" -> {
                listSkills(terminal, completionState);
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
                SkillInfo skill = completionState.skillBySlashName(command);
                if (skill != null) {
                    if (rest.isBlank()) {
                        terminal.writer().println("usage: " + command + " <task>");
                        terminal.writer().println(dim(skill.getDescription()));
                        terminal.flush();
                        return false;
                    }
                    String task = "请先调用 skills 工具执行 invoke，name=`" + skill.getName()
                            + "`，加载并遵循该技能，然后完成用户任务：\n\n" + rest;
                    RunRecord record = runCommand.runTask(task, false, true, session.sessionKey());
                    session.setLastRunId(record.getRunId());
                    terminal.flush();
                    return false;
                }
                printSlashSuggestions(command, terminal, completionState);
                terminal.flush();
                return false;
            }
        }
    }

    private static void printSlashSuggestions(String prefix,
                                              Terminal terminal,
                                              ShellCompletionState completionState) {
        List<String> suggestions = new ArrayList<>();
        for (ShellCommandHelp command : SHELL_COMMANDS) {
            if (command.name().startsWith(prefix)) {
                suggestions.add("%-22s %s".formatted(command.name(), command.description()));
            }
        }
        for (SkillInfo skill : completionState.skills()) {
            String name = "/" + skill.getName();
            if (name.startsWith(prefix)) {
                suggestions.add("%-22s %s".formatted(name, summarize(skill.getDescription())));
            }
        }
        if (suggestions.isEmpty()) {
            terminal.writer().println("unknown command: " + prefix);
            return;
        }
        printSection(terminal, "matches");
        for (String suggestion : suggestions) {
            terminal.writer().println("  " + suggestion);
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
        session.setLastRunId(selected.getRunId());
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

    private static void printSlashPalette(Terminal terminal, ShellCompletionState completionState) {
        printSection(terminal, "command palette");
        terminal.writer().println("commands");
        for (ShellCommandHelp command : SHELL_COMMANDS) {
            terminal.writer().println("  %-22s %s".formatted(command.name(), command.description()));
        }
        terminal.writer().println();
        terminal.writer().println("skills");
        if (completionState.skills().isEmpty()) {
            terminal.writer().println("  (no skills available)");
        } else {
            for (SkillInfo skill : completionState.skills()) {
                terminal.writer().println("  %-22s %s".formatted(
                        "/" + skill.getName(),
                        summarize(skill.getDescription())
                ));
            }
        }
        terminal.writer().println();
        terminal.writer().println(dim("Type /<command> or /<skill> <task>. Tab completion is enabled when the terminal forwards Tab to JLine."));
        terminal.flush();
    }

    private static void listTools(String runId,
                                  Terminal terminal,
                                  RunService runService,
                                  ShellSession session) throws Exception {
        String targetRunId = firstNonBlank(runId, session.lastRunId());
        printSection(terminal, "tools");
        if (targetRunId == null || targetRunId.isBlank()) {
            terminal.writer().println("usage: /tools <runId>");
            terminal.flush();
            return;
        }
        List<ToolCallView> tools = toolCalls(runService, targetRunId);
        if (tools.isEmpty()) {
            terminal.writer().println("(no tool calls)");
        } else {
            for (ToolCallView tool : tools) {
                terminal.writer().println("%2d. %-18s %-7s %s".formatted(
                        tool.index(),
                        tool.name(),
                        tool.status(),
                        summarize(tool.output())
                ));
            }
            terminal.writer().println();
            terminal.writer().println("Use /tool " + targetRunId + " <n> to expand, or /tool <n> for the last run.");
        }
        terminal.flush();
    }

    private static void showTool(String args,
                                 Terminal terminal,
                                 RunService runService,
                                 ShellSession session) throws Exception {
        String[] parts = args == null || args.isBlank() ? new String[0] : args.split("\\s+");
        String runId;
        String indexText;
        if (parts.length == 1) {
            runId = session.lastRunId();
            indexText = parts[0];
        } else if (parts.length >= 2 && "last".equalsIgnoreCase(parts[0])) {
            runId = session.lastRunId();
            indexText = parts[1];
        } else if (parts.length >= 2) {
            runId = parts[0];
            indexText = parts[1];
        } else {
            runId = null;
            indexText = null;
        }
        printSection(terminal, "tool result");
        if (runId == null || runId.isBlank() || indexText == null || indexText.isBlank()) {
            terminal.writer().println("usage: /tool [runId] <n>");
            terminal.flush();
            return;
        }
        int index;
        try {
            index = Integer.parseInt(indexText);
        } catch (NumberFormatException e) {
            terminal.writer().println("invalid tool index: " + indexText);
            terminal.flush();
            return;
        }
        if ((parts.length == 1 || (parts.length >= 2 && "last".equalsIgnoreCase(parts[0])))
                && session.lastRenderer() != null
                && session.lastRenderer().toggleTool(index)) {
            return;
        }
        List<ToolCallView> tools = toolCalls(runService, runId);
        if (index < 1 || index > tools.size()) {
            terminal.writer().println("tool index out of range: " + index);
            terminal.flush();
            return;
        }
        ToolCallView tool = tools.get(index - 1);
        terminal.writer().println("▸ " + tool.name() + "  " + tool.status());
        if (tool.request() != null && !tool.request().isBlank()) {
            terminal.writer().println();
            terminal.writer().println("request:");
            printBlock(terminal, tool.request());
        }
        if (tool.output() != null && !tool.output().isBlank()) {
            terminal.writer().println();
            terminal.writer().println("result:");
            printBlock(terminal, tool.output());
        }
        terminal.flush();
    }

    private static void listSkills(Terminal terminal, ShellCompletionState completionState) {
        printSection(terminal, "skills");
        List<SkillInfo> skills = completionState.skills();
        if (skills.isEmpty()) {
            terminal.writer().println("(no skills available)");
        } else {
            for (SkillInfo skill : skills) {
                terminal.writer().println("%-30s %-10s %s".formatted(
                        "/" + skill.getName(),
                        firstNonBlank(skill.getSource(), ""),
                        summarize(skill.getDescription())
                ));
            }
            terminal.writer().println();
            terminal.writer().println(dim("Use /<skill> <task>, for example: /weather 长沙明天天气"));
        }
        terminal.flush();
    }

    private static List<ToolCallView> toolCalls(RunService runService, String runId) throws Exception {
        List<ExecutionEvent> events = runService.readEvents(runId, 0);
        LinkedHashMap<String, MutableToolCall> calls = new LinkedHashMap<>();
        for (ExecutionEvent event : events) {
            if (event.getType() != ExecutionEvent.EventType.TOOL_START
                    && event.getType() != ExecutionEvent.EventType.TOOL_END
                    && event.getType() != ExecutionEvent.EventType.TOOL_OUTPUT
                    && event.getType() != ExecutionEvent.EventType.TOOL_ERROR) {
                continue;
            }
            String toolId = stringValue(event.getMetadata().get("toolId"));
            if (toolId == null || toolId.isBlank()) {
                toolId = firstNonBlank(stringValue(event.getMetadata().get("toolName")), "tool") + "-" + calls.size();
            }
            MutableToolCall call = calls.computeIfAbsent(toolId, id -> new MutableToolCall());
            call.name = firstNonBlank(stringValue(event.getMetadata().get("toolName")), call.name, "tool");
            call.request = firstNonBlank(stringValue(event.getMetadata().get("request")), call.request, "");
            if (event.getType() == ExecutionEvent.EventType.TOOL_ERROR) {
                call.status = "ERROR";
                call.output = event.getContent();
            } else if (event.getType() == ExecutionEvent.EventType.TOOL_OUTPUT) {
                call.output = event.getContent();
            } else if (event.getType() == ExecutionEvent.EventType.TOOL_END) {
                call.status = "DONE";
            } else if (call.status == null) {
                call.status = "RUNNING";
            }
        }
        List<ToolCallView> views = new ArrayList<>();
        int index = 1;
        for (MutableToolCall call : calls.values()) {
            views.add(new ToolCallView(index++, call.name, firstNonBlank(call.status, "DONE"), call.request, call.output));
        }
        return views;
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

    private static void configureCompletion(LineReader reader) {
        reader.setOpt(LineReader.Option.AUTO_MENU);
        reader.setOpt(LineReader.Option.AUTO_LIST);
        reader.setOpt(LineReader.Option.AUTO_MENU_LIST);
        reader.setOpt(LineReader.Option.AUTO_GROUP);
        reader.setOpt(LineReader.Option.LIST_PACKED);
        reader.unsetOpt(LineReader.Option.INSERT_TAB);

        String backTab = KeyMap.key(reader.getTerminal(), org.jline.utils.InfoCmp.Capability.back_tab);
        String tab = KeyMap.ctrl('I');
        for (var keyMap : reader.getKeyMaps().values()) {
            keyMap.bind(new Reference(LineReader.COMPLETE_WORD), tab, "\t");
            if (backTab != null && !backTab.isBlank()) {
                keyMap.bind(new Reference(LineReader.REVERSE_MENU_COMPLETE), backTab);
            }
        }
    }

    private static void printInputTop(Terminal terminal) {
        int width = Math.max(40, Math.min(terminal.getWidth(), 160));
        terminal.writer().println("╞" + "═".repeat(width - 2) + "╡");
        terminal.flush();
    }

    private static void clearSubmittedInputBox(Terminal terminal) {
        if (System.console() != null) {
            terminal.writer().print("\r\033[2K\033[1A\033[2K\033[1A\033[2K\r");
        } else {
            int width = Math.max(40, Math.min(terminal.getWidth(), 160));
            terminal.writer().println();
            terminal.writer().println("╘" + "═".repeat(width - 2) + "╛");
        }
        terminal.flush();
    }

    private static boolean confirmExit(LineReader reader, Terminal terminal) {
        try {
            String answer = reader.readLine("Exit JobClaw? [y/N] ").trim();
            return "y".equalsIgnoreCase(answer) || "yes".equalsIgnoreCase(answer);
        } catch (UserInterruptException ignored) {
            return false;
        } catch (EndOfFileException ignored) {
            return true;
        }
    }

    private static void printSection(Terminal terminal, String name) {
        terminal.writer().println();
        terminal.writer().println("----- " + name + " " + "-".repeat(Math.max(1, 62 - name.length())));
    }

    private static void printBlock(Terminal terminal, String text) {
        int width = Math.max(40, Math.min(terminal.getWidth(), 140));
        int contentWidth = width - 4;
        terminal.writer().println("╭" + "─".repeat(width - 2) + "╮");
        for (String line : text.split("\\R", -1)) {
            String rest = line;
            do {
                String part = clip(rest, contentWidth);
                terminal.writer().println("│ " + pad(part, contentWidth) + " │");
                rest = visibleLength(rest) > contentWidth
                        ? rest.substring(Math.min(rest.length(), Math.max(1, contentWidth - 3)))
                        : "";
            } while (!rest.isEmpty());
        }
        terminal.writer().println("╰" + "─".repeat(width - 2) + "╯");
    }

    private static void completeShellInput(LineReader reader, org.jline.reader.ParsedLine line, List<Candidate> candidates) {
        String word = line.word();
        String buffer = line.line();
        if (!buffer.startsWith("/") && !buffer.equals("?")) {
            return;
        }
        for (ShellCommandHelp command : SHELL_COMMANDS) {
            if (word == null || word.isBlank() || command.name().startsWith(word)) {
                candidates.add(new Candidate(
                        command.name(),
                        command.name(),
                        "commands",
                        command.description(),
                        null,
                        null,
                        true
                ));
            }
        }
        for (SkillInfo skill : activeCompletionState.skills()) {
            String candidate = "/" + skill.getName();
            if (word == null || word.isBlank() || candidate.startsWith(word)) {
                candidates.add(new Candidate(
                        candidate,
                        candidate,
                        "skills",
                        firstNonBlank(skill.getDescription(), "skill"),
                        null,
                        null,
                        true
                ));
            }
        }
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
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

    private record ShellCommandHelp(String name, String description) {
    }

    private record ShellCompletionState(List<SkillInfo> skills) {
        private ShellCompletionState {
            skills = skills == null
                    ? List.of()
                    : skills.stream()
                    .filter(skill -> skill.getName() != null && !skill.getName().isBlank())
                    .sorted(Comparator.comparing(SkillInfo::getName))
                    .toList();
        }

        static ShellCompletionState empty() {
            return new ShellCompletionState(List.of());
        }

        SkillInfo skillBySlashName(String slashName) {
            if (slashName == null || !slashName.startsWith("/")) {
                return null;
            }
            String name = slashName.substring(1);
            return skills.stream()
                    .filter(skill -> skill.getName().equals(name))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static final class ShellSession {
        private String sessionKey;
        private String lastRunId;
        private TranscriptTuiRenderer lastRenderer;

        private ShellSession(String sessionKey) {
            this.sessionKey = sessionKey;
        }

        private String sessionKey() {
            return sessionKey;
        }

        private void setSessionKey(String sessionKey) {
            this.sessionKey = sessionKey;
        }

        private String lastRunId() {
            return lastRunId;
        }

        private void setLastRunId(String lastRunId) {
            this.lastRunId = lastRunId;
        }

        private TranscriptTuiRenderer lastRenderer() {
            return lastRenderer;
        }

        private void setLastRenderer(TranscriptTuiRenderer lastRenderer) {
            this.lastRenderer = lastRenderer;
        }
    }

    private static final class MutableToolCall {
        private String name;
        private String status;
        private String request;
        private String output;
    }

    private record ToolCallView(int index, String name, String status, String request, String output) {
    }
}
