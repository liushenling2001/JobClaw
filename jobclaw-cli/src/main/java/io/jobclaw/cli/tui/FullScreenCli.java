package io.jobclaw.cli.tui;

import io.jobclaw.config.Config;
import io.jobclaw.run.RunRecord;
import io.jobclaw.run.RunRequest;
import io.jobclaw.run.RunService;
import io.jobclaw.skills.SkillInfo;
import io.jobclaw.skills.SkillsService;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Grok Build-inspired fullscreen terminal client backed by the existing RunService. */
public final class FullScreenCli {
    private static final String ESC = "\033[";
    private static final String RESET = ESC + "0m";
    private static final String ORANGE = ESC + "38;5;209m";
    private static final String CYAN = ESC + "38;5;81m";
    private static final String GREEN = ESC + "38;5;114m";
    private static final String RED = ESC + "38;5;203m";
    private static final String DIM = ESC + "38;5;245m";
    private static final String USER_BG = ESC + "48;5;238m";
    private static final Pattern ANSI_PATTERN = Pattern.compile("\\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final Pattern TOOL_ROW_PATTERN = Pattern.compile("^[▸▾] .* \\[(\\d+)]$");
    private static final List<Command> COMMANDS = List.of(
            new Command("/help", "Show commands and keyboard shortcuts"),
            new Command("/resume", "Choose a recent conversation"),
            new Command("/status", "Show recent runs"),
            new Command("/runs", "Show more recent runs"),
            new Command("/logs", "Show events for a run"),
            new Command("/artifacts", "Show artifacts for a run"),
            new Command("/tools", "List tool calls in this transcript"),
            new Command("/tool", "Expand or collapse a tool call"),
            new Command("/skills", "List available skills"),
            new Command("/attach", "Replay a run in this transcript"),
            new Command("/model", "Show the active model"),
            new Command("/clear", "Clear the visible transcript"),
            new Command("/new", "Start a new conversation"),
            new Command("/exit", "Exit JobClaw")
    );

    private final Terminal terminal;
    private final CompletableFuture<ConfigurableApplicationContext> runtimeFuture;
    private final boolean wslImeMode = System.getenv("WSL_DISTRO_NAME") != null;
    private final CliTranscriptModel transcript = new CliTranscriptModel();
    private final Queue<io.jobclaw.agent.ExecutionEvent> eventQueue = new ConcurrentLinkedQueue<>();
    private final Queue<String> promptQueue = new ArrayDeque<>();
    private final StringBuilder composer = new StringBuilder();
    private final List<String> history = new ArrayList<>();
    private final Map<Integer, Integer> toolRows = new HashMap<>();
    private final ExecutorService runExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "jobclaw-cli-runner");
        thread.setDaemon(true);
        return thread;
    });

    private ConfigurableApplicationContext context;
    private RunService runService;
    private Config config;
    private List<SkillInfo> skills = List.of();
    private List<RunRecord> recentConversations = List.of();
    private CompletableFuture<RunRecord> activeRun;
    private String sessionKey;
    private String lastRunId;
    private String model = "loading runtime";
    private String runtimeError;
    private int cursor;
    private int historyIndex = -1;
    private int menuIndex;
    private int selectedTool = -1;
    private boolean toolFocus;
    private boolean confirmExit;
    private boolean exit;
    private volatile boolean interruptRequested;
    private List<RunRecord> resumeChoices;
    private int resumeIndex;
    private long startedAt = System.nanoTime();
    private boolean dirty = true;

    public FullScreenCli(Terminal terminal, CompletableFuture<ConfigurableApplicationContext> runtimeFuture) {
        this.terminal = terminal;
        this.runtimeFuture = runtimeFuture;
        loadHistory();
    }

    public int run() {
        Attributes original = terminal.enterRawMode();
        Terminal.SignalHandler originalIntHandler = terminal.handle(Terminal.Signal.INT,
                signal -> interruptRequested = true);
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        terminal.writer().print(ESC + "?1049h"
                + (wslImeMode ? "" : ESC + "?1000h" + ESC + "?1006h")
                + ESC + "?25h" + ESC + "2J" + ESC + "H");
        terminal.flush();
        System.setOut(new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
        try {
            while (!exit) {
                if (interruptRequested) {
                    interruptRequested = false;
                    confirmExit = true;
                    dirty = true;
                }
                pollRuntime();
                drainEvents();
                pollRun();
                int key = terminal.reader().read(40L);
                if (key >= 0) {
                    handleKey(key);
                }
                if (dirty) {
                    render();
                    dirty = false;
                }
            }
            return 0;
        } catch (Exception e) {
            return 1;
        } finally {
            if (activeRun != null) {
                activeRun.cancel(true);
            }
            runExecutor.shutdownNow();
            if (context != null) {
                context.close();
            }
            System.setOut(originalOut);
            System.setErr(originalErr);
            terminal.handle(Terminal.Signal.INT, originalIntHandler);
            terminal.setAttributes(original);
            terminal.writer().print((wslImeMode ? "" : ESC + "?1006l" + ESC + "?1000l")
                    + ESC + "?25h" + ESC + "?1049l" + RESET);
            terminal.flush();
        }
    }

    private void pollRuntime() {
        if (context != null || runtimeError != null || !runtimeFuture.isDone()) {
            return;
        }
        try {
            context = runtimeFuture.join();
            runService = context.getBean(RunService.class);
            config = context.getBean(Config.class);
            skills = context.getBean(SkillsService.class).listSkills().stream()
                    .sorted(Comparator.comparing(SkillInfo::getName)).toList();
            model = config.getAgent().getModel();
            refreshRecentConversations();
            transcript.status("Ready");
            dirty = true;
            startNextPrompt();
        } catch (Exception e) {
            runtimeError = rootMessage(e);
            transcript.addError("Runtime failed to load: " + runtimeError);
            dirty = true;
        }
    }

    private void drainEvents() {
        io.jobclaw.agent.ExecutionEvent event;
        boolean changed = false;
        while ((event = eventQueue.poll()) != null) {
            transcript.accept(event);
            changed = true;
        }
        dirty |= changed;
    }

    private void pollRun() {
        if (activeRun == null || !activeRun.isDone()) {
            return;
        }
        try {
            RunRecord record = activeRun.join();
            lastRunId = record.getRunId();
            sessionKey = record.getSessionKey();
            refreshRecentConversations();
            transcript.status("Worked for " + elapsed() + " · " + record.getRunId());
        } catch (Exception e) {
            transcript.addError(rootMessage(e));
        } finally {
            activeRun = null;
            startedAt = System.nanoTime();
            dirty = true;
            startNextPrompt();
        }
    }

    private void startNextPrompt() {
        if (activeRun != null || runService == null || promptQueue.isEmpty()) {
            return;
        }
        String task = promptQueue.remove();
        transcript.addUser(task);
        startedAt = System.nanoTime();
        activeRun = CompletableFuture.supplyAsync(() -> {
            try {
                String cwd = System.getProperty("user.dir");
                return runService.startForeground(new RunRequest(
                        task, sessionKey, cwd, cwd, "cli", "ask", "workspace-write", null
                ), eventQueue::add);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, runExecutor);
        dirty = true;
    }

    private void refreshRecentConversations() {
        if (runService == null) return;
        try {
            Map<String, RunRecord> bySession = new LinkedHashMap<>();
            for (RunRecord run : runService.listRuns(100)) {
                String key = run.getSessionKey() == null || run.getSessionKey().isBlank()
                        ? run.getRunId() : run.getSessionKey();
                bySession.putIfAbsent(key, run);
            }
            recentConversations = new ArrayList<>(bySession.values()).stream().limit(5).toList();
        } catch (Exception ignored) {
            recentConversations = List.of();
        }
    }

    private void handleKey(int key) throws Exception {
        if (confirmExit) {
            if (key == 'y' || key == 'Y') {
                exit = true;
            } else if (key == 'n' || key == 'N' || key == 27 || key == 3 || key == 10 || key == 13) {
                confirmExit = false;
            }
            dirty = true;
            return;
        }
        if (resumeChoices != null) {
            handlePickerKey(key);
            return;
        }
        if (key == 3 || (key == 4 && composer.isEmpty())) {
            confirmExit = true;
            dirty = true;
            return;
        }
        if (key == 9) {
            handleTab();
            return;
        }
        if (key == 27) {
            handleEscapeSequence();
            return;
        }
        if (key == 10 || key == 13) {
            if (toolFocus && selectedTool > 0) {
                transcript.toggleTool(selectedTool);
                dirty = true;
            } else {
                submitComposer();
            }
            return;
        }
        if (key == 127 || key == 8) {
            if (cursor > 0) {
                composer.deleteCharAt(--cursor);
                resetMenuSelection();
            }
            dirty = true;
            return;
        }
        if (key >= 32 && key != 127) {
            composer.insert(cursor, (char) key);
            cursor++;
            toolFocus = false;
            resetMenuSelection();
            dirty = true;
        }
    }

    private void handleEscapeSequence() throws Exception {
        int second = terminal.reader().read(5L);
        if (second != '[') {
            toolFocus = false;
            dirty = true;
            return;
        }
        int code = terminal.reader().read(5L);
        switch (code) {
            case 'A' -> moveUp();
            case 'B' -> moveDown();
            case 'C' -> { if (cursor < composer.length()) cursor++; }
            case 'D' -> { if (cursor > 0) cursor--; }
            case '<' -> handleMouseSequence();
            default -> { }
        }
        dirty = true;
    }

    private void handleMouseSequence() throws Exception {
        StringBuilder sequence = new StringBuilder();
        int terminator = -1;
        while (sequence.length() < 40) {
            int value = terminal.reader().read(5L);
            if (value < 0) return;
            if (value == 'M' || value == 'm') {
                terminator = value;
                break;
            }
            sequence.append((char) value);
        }
        if (terminator != 'M') return;
        String[] values = sequence.toString().split(";");
        if (values.length != 3) return;
        try {
            int button = Integer.parseInt(values[0]);
            int row = Integer.parseInt(values[2]);
            if ((button & 3) != 0) return;
            Integer toolIndex = toolRows.get(row);
            if (toolIndex != null) {
                toolFocus = true;
                selectedTool = toolIndex;
                transcript.toggleTool(toolIndex);
            } else {
                toolFocus = false;
                selectedTool = -1;
            }
        } catch (NumberFormatException ignored) { }
    }

    private void moveUp() {
        List<Suggestion> suggestions = suggestions();
        if (!suggestions.isEmpty()) {
            menuIndex = Math.floorMod(menuIndex - 1, suggestions.size());
        } else if (toolFocus) {
            List<CliTranscriptModel.ToolBlock> tools = transcript.tools();
            if (!tools.isEmpty()) {
                int at = toolPosition(tools, selectedTool);
                selectedTool = tools.get(Math.floorMod(at - 1, tools.size())).index();
            }
        } else if (!history.isEmpty()) {
            historyIndex = historyIndex < 0 ? history.size() - 1 : Math.max(0, historyIndex - 1);
            setComposer(history.get(historyIndex));
        }
    }

    private void moveDown() {
        List<Suggestion> suggestions = suggestions();
        if (!suggestions.isEmpty()) {
            menuIndex = (menuIndex + 1) % suggestions.size();
        } else if (toolFocus) {
            List<CliTranscriptModel.ToolBlock> tools = transcript.tools();
            if (!tools.isEmpty()) {
                int at = toolPosition(tools, selectedTool);
                selectedTool = tools.get((at + 1) % tools.size()).index();
            }
        } else if (historyIndex >= 0) {
            historyIndex++;
            if (historyIndex >= history.size()) {
                historyIndex = -1;
                setComposer("");
            } else {
                setComposer(history.get(historyIndex));
            }
        }
    }

    private int toolPosition(List<CliTranscriptModel.ToolBlock> tools, int index) {
        for (int i = 0; i < tools.size(); i++) {
            if (tools.get(i).index() == index) return i;
        }
        return tools.size() - 1;
    }

    private void handleTab() {
        List<Suggestion> suggestions = suggestions();
        if (!suggestions.isEmpty()) {
            setComposer(suggestions.get(Math.min(menuIndex, suggestions.size() - 1)).value + " ");
            return;
        }
        List<CliTranscriptModel.ToolBlock> tools = transcript.tools();
        toolFocus = !toolFocus && !tools.isEmpty();
        selectedTool = toolFocus ? tools.get(tools.size() - 1).index() : -1;
        dirty = true;
    }

    private void submitComposer() throws Exception {
        String input = composer.toString().trim();
        if (input.isEmpty()) {
            return;
        }
        List<Suggestion> visibleSuggestions = suggestions();
        boolean exactSuggestion = false;
        for (Suggestion suggestion : visibleSuggestions) {
            exactSuggestion |= suggestion.value.equals(input);
        }
        if (input.startsWith("/") && !visibleSuggestions.isEmpty() && !exactSuggestion) {
            input = visibleSuggestions.get(Math.min(menuIndex, visibleSuggestions.size() - 1)).value;
        }
        setComposer("");
        addHistory(input);
        if (input.startsWith("/")) {
            executeCommand(input);
            return;
        }
        promptQueue.add(input);
        transcript.status(runService == null ? "Queued until runtime is ready" :
                activeRun == null ? "Starting" : promptQueue.size() + " queued");
        startNextPrompt();
        dirty = true;
    }

    private void executeCommand(String input) throws Exception {
        String[] parts = input.split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String argument = parts.length > 1 ? parts[1].trim() : "";
        SkillInfo skill = skill(command.substring(1));
        if (skill != null && COMMANDS.stream().noneMatch(item -> item.name.equals(command))) {
            if (argument.isBlank()) {
                transcript.addSystem(command + " requires a task\n" + skill.getDescription());
            } else {
                promptQueue.add("请先调用 skills 工具执行 invoke，name=`" + skill.getName()
                        + "`，加载并遵循该技能，然后完成用户任务：\n\n" + argument);
                startNextPrompt();
            }
            return;
        }
        switch (command) {
            case "/exit", "/quit" -> confirmExit = true;
            case "/clear" -> transcript.clear();
            case "/new" -> { sessionKey = null; lastRunId = null; transcript.clear(); }
            case "/model" -> transcript.addSystem("Active model: " + model);
            case "/help" -> transcript.addSystem(helpText());
            case "/skills" -> transcript.addSystem(skillsText());
            case "/tools" -> transcript.addSystem(toolsText());
            case "/tool" -> toggleTool(argument);
            case "/status" -> showRuns(10);
            case "/runs" -> showRuns(20);
            case "/logs" -> showLogs(argument);
            case "/artifacts" -> showArtifacts(argument);
            case "/attach" -> attach(argument, false);
            case "/resume" -> openResume(argument);
            default -> transcript.addSystem("Unknown command: " + command + "\nType / to browse commands and skills.");
        }
        dirty = true;
    }

    private void toggleTool(String argument) {
        try {
            int index = Integer.parseInt(argument);
            if (!transcript.toggleTool(index)) transcript.addSystem("Tool " + index + " was not found.");
        } catch (NumberFormatException e) {
            transcript.addSystem("Usage: /tool <number>");
        }
    }

    private void showRuns(int limit) throws Exception {
        if (!requireRuntime()) return;
        StringBuilder text = new StringBuilder("Recent runs\n");
        for (RunRecord run : runService.listRuns(limit)) {
            text.append(run.getRunId()).append("  ").append(run.getStatus()).append("  ")
                    .append(compact(run.getTask(), 80)).append('\n');
        }
        transcript.addSystem(text.toString().trim());
    }

    private void showLogs(String argument) throws Exception {
        if (!requireRuntime()) return;
        String id = argument.isBlank() ? lastRunId : argument;
        if (id == null) { transcript.addSystem("Usage: /logs <runId>"); return; }
        StringBuilder text = new StringBuilder("Events for ").append(id).append('\n');
        runService.readEvents(id, 80).forEach(event -> text.append(event.getType()).append("  ")
                .append(compact(event.getContent(), 140)).append('\n'));
        transcript.addSystem(text.toString().trim());
    }

    private void showArtifacts(String argument) throws Exception {
        if (!requireRuntime()) return;
        String id = argument.isBlank() ? lastRunId : argument;
        if (id == null) { transcript.addSystem("Usage: /artifacts <runId>"); return; }
        List<String> artifacts = runService.readArtifacts(id);
        transcript.addSystem(artifacts.isEmpty() ? "No artifacts for " + id :
                "Artifacts for " + id + "\n" + String.join("\n", artifacts));
    }

    private void openResume(String argument) throws Exception {
        if (!requireRuntime()) return;
        if (!argument.isBlank()) {
            attach(argument, true);
            return;
        }
        Map<String, RunRecord> recentConversations = new LinkedHashMap<>();
        for (RunRecord run : runService.listRuns(100)) {
            String key = run.getSessionKey() == null || run.getSessionKey().isBlank()
                    ? run.getRunId() : run.getSessionKey();
            recentConversations.putIfAbsent(key, run);
        }
        resumeChoices = new ArrayList<>(recentConversations.values()).stream().limit(20).toList();
        resumeIndex = 0;
        if (resumeChoices.isEmpty()) {
            resumeChoices = null;
            transcript.addSystem("No recent conversations.");
        }
    }

    private void attach(String runId, boolean resume) throws Exception {
        if (!requireRuntime()) return;
        if (runId == null || runId.isBlank()) {
            transcript.addSystem("Usage: " + (resume ? "/resume" : "/attach") + " <runId>");
            return;
        }
        RunRecord record = runService.getRequired(runId);
        transcript.clear();
        transcript.addUser(record.getTask());
        runService.readEvents(runId, 2_000).forEach(transcript::accept);
        transcript.status((resume ? "Resumed " : "Attached ") + runId);
        lastRunId = runId;
        if (resume) sessionKey = record.getSessionKey();
    }

    private void handlePickerKey(int key) throws Exception {
        if (key == 3) {
            resumeChoices = null;
        } else if (key == 27) {
            int second = terminal.reader().read(5L);
            if (second != '[') {
                resumeChoices = null;
            } else {
                int code = terminal.reader().read(5L);
                if (code == 'A') resumeIndex = Math.floorMod(resumeIndex - 1, resumeChoices.size());
                if (code == 'B') resumeIndex = (resumeIndex + 1) % resumeChoices.size();
            }
        } else if (key == 10 || key == 13) {
            RunRecord selected = resumeChoices.get(resumeIndex);
            resumeChoices = null;
            attach(selected.getRunId(), true);
        } else if (key == 'k') {
            resumeIndex = Math.floorMod(resumeIndex - 1, resumeChoices.size());
        } else if (key == 'j') {
            resumeIndex = (resumeIndex + 1) % resumeChoices.size();
        } else if (key >= '1' && key <= '9') {
            int selected = key - '1';
            if (selected < resumeChoices.size()) resumeIndex = selected;
        } else if (key == '[') {
            // ignored; arrows are handled by j/k and numeric shortcuts in this lightweight picker
        }
        dirty = true;
    }

    private boolean requireRuntime() {
        if (runService != null) return true;
        transcript.addSystem(runtimeError == null ? "Runtime is still loading." : "Runtime unavailable: " + runtimeError);
        return false;
    }

    private List<Suggestion> suggestions() {
        String input = composer.toString();
        if (!input.startsWith("/") || input.contains(" ")) return List.of();
        List<Suggestion> result = new ArrayList<>();
        for (Command command : COMMANDS) {
            if (command.name.startsWith(input)) result.add(new Suggestion(command.name, command.description));
        }
        for (SkillInfo skill : skills) {
            String value = "/" + skill.getName();
            if (value.startsWith(input)) result.add(new Suggestion(value, compact(skill.getDescription(), 70)));
        }
        return result.stream().limit(10).toList();
    }

    private SkillInfo skill(String name) {
        return skills.stream().filter(item -> item.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private String helpText() {
        StringBuilder text = new StringBuilder("Commands\n");
        COMMANDS.forEach(command -> text.append(String.format("%-14s %s%n", command.name, command.description)));
        return text.append("\nTab: complete command / focus tools\nEnter: send / expand selected tool\nCtrl+C: confirm exit").toString();
    }

    private String skillsText() {
        if (skills.isEmpty()) return "No skills loaded.";
        StringBuilder text = new StringBuilder("Skills\n");
        skills.forEach(skill -> text.append('/').append(skill.getName()).append("  ")
                .append(compact(skill.getDescription(), 100)).append('\n'));
        return text.toString().trim();
    }

    private String toolsText() {
        List<CliTranscriptModel.ToolBlock> tools = transcript.tools();
        if (tools.isEmpty()) return "No tool calls in this transcript.";
        StringBuilder text = new StringBuilder("Tool calls\n");
        tools.forEach(tool -> text.append(tool.index()).append("  ").append(tool.name()).append("  ")
                .append(tool.status()).append('\n'));
        return text.toString().trim();
    }

    private void render() {
        int width = Math.max(50, terminal.getWidth());
        int height = Math.max(18, terminal.getHeight());
        List<Suggestion> suggestions = suggestions();
        int overlayRows = resumeChoices != null ? Math.min(12, resumeChoices.size() + 3) : Math.min(8, suggestions.size());
        int bodyHeight = Math.max(4, height - 8 - overlayRows);
        List<String> body = transcript.blocks().isEmpty()
                ? renderWelcome(width, bodyHeight)
                : renderTranscript(width);
        int from = Math.max(0, body.size() - bodyHeight);
        List<String> screen = new ArrayList<>();
        screen.add(ORANGE + " JobClaw" + RESET + "  " + DIM + model + RESET);
        screen.add(DIM + " " + compact(System.getProperty("user.dir"), width - 2) + RESET);
        screen.add(DIM + "─".repeat(width) + RESET);
        for (int i = from; i < body.size(); i++) screen.add(body.get(i));
        while (screen.size() < 3 + bodyHeight) screen.add("");
        if (resumeChoices != null) screen.addAll(renderResumePicker(width));
        else screen.addAll(renderSuggestions(suggestions, width));
        String activity = activityText();
        screen.add(" " + (activeRun != null ? CYAN + spinner() + " " : DIM) + activity + RESET);
        screen.add(ORANGE + "╭" + "─".repeat(width - 2) + "╮" + RESET);
        ComposerView composerView = composerView(width - 6);
        String input = "> " + composerView.text();
        screen.add(ORANGE + "│" + RESET + " " + input
                + pad(width - 3 - displayWidth(input)) + ORANGE + "│" + RESET);
        screen.add(ORANGE + "╰" + "─".repeat(width - 2) + "╯" + RESET);
        screen.add(DIM + " / commands  ·  Tab tools  ·  Ctrl+C exit" + (promptQueue.isEmpty() ? "" : "  ·  " + promptQueue.size() + " queued") + RESET);

        toolRows.clear();
        for (int row = 0; row < screen.size(); row++) {
            Matcher matcher = TOOL_ROW_PATTERN.matcher(stripAnsi(screen.get(row)).trim());
            if (matcher.matches()) toolRows.put(row + 1, Integer.parseInt(matcher.group(1)));
        }

        // Windows Terminal owns the IME composition window for WSL. Synchronized
        // output and mouse reporting can reset that composition, so WSL uses the
        // plain repaint path and keeps the cursor visible throughout.
        StringBuilder out = new StringBuilder();
        if (!wslImeMode) {
            out.append(ESC).append("?2026h");
        }
        out.append(ESC).append('H');
        for (int row = 0; row < height; row++) {
            String line = row < screen.size() ? screen.get(row) : "";
            out.append(ESC).append("2K").append(clipAnsi(line, width));
            if (row + 1 < height) out.append('\n');
        }
        int composerRow = Math.min(height - 1, 3 + bodyHeight + overlayRows + 3);
        int cursorColumn = Math.min(width - 1, 5 + composerView.cursorOffset());
        out.append(ESC).append(composerRow).append(';').append(cursorColumn).append('H')
                .append(ESC).append("?25h");
        if (!wslImeMode) {
            out.append(ESC).append("?2026l");
        }
        terminal.writer().print(out);
        terminal.flush();
    }

    private List<String> renderWelcome(int width, int availableHeight) {
        int panelWidth = Math.max(46, Math.min(width - 4, 100));
        boolean wide = panelWidth >= 76 && availableHeight >= 13;
        List<String> panel = wide ? renderWideWelcome(panelWidth) : renderNarrowWelcome(panelWidth);
        List<String> result = new ArrayList<>();
        int topPadding = Math.max(0, (availableHeight - panel.size()) / 3);
        for (int i = 0; i < topPadding; i++) result.add("");
        for (String line : panel) result.add(center(line, width));
        return result;
    }

    private List<String> renderWideWelcome(int panelWidth) {
        int inner = panelWidth - 2;
        int leftWidth = 27;
        int rightWidth = inner - leftWidth - 1;
        List<String> lines = new ArrayList<>();
        lines.add(ORANGE + "╭─ JobClaw " + "─".repeat(panelWidth - 12) + "╮" + RESET);
        lines.add(welcomeColumns("", "Task-oriented coding runtime", leftWidth, rightWidth));
        lines.add(welcomeColumns("      JOBCLAW", "Get started", leftWidth, rightWidth));
        lines.add(welcomeColumns("", "  Type a task and press Enter", leftWidth, rightWidth));
        lines.add(welcomeColumns("  " + model, "  /resume   continue a conversation", leftWidth, rightWidth));
        lines.add(welcomeColumns(context == null ? "  starting runtime" : "  runtime ready",
                "  /skills   browse available skills", leftWidth, rightWidth));
        lines.add(welcomeColumns("", "  /         browse all commands", leftWidth, rightWidth));
        lines.add(ORANGE + "├" + "─".repeat(panelWidth - 2) + "┤" + RESET);
        lines.add(welcomeFullRow("Recent activity", inner));
        appendRecentRows(lines, inner, Math.min(3, recentConversations.size()));
        lines.add(ORANGE + "╰" + "─".repeat(panelWidth - 2) + "╯" + RESET);
        return lines;
    }

    private List<String> renderNarrowWelcome(int panelWidth) {
        int inner = panelWidth - 2;
        List<String> lines = new ArrayList<>();
        lines.add(ORANGE + "╭─ JobClaw " + "─".repeat(panelWidth - 12) + "╮" + RESET);
        lines.add(welcomeFullRow("Task-oriented coding runtime", inner));
        lines.add(welcomeFullRow(context == null ? "Loading runtime..." : model + " · runtime ready", inner));
        lines.add(ORANGE + "├" + "─".repeat(panelWidth - 2) + "┤" + RESET);
        lines.add(welcomeFullRow("Type a task, or use /resume, /skills, and /help", inner));
        if (recentConversations.isEmpty()) {
            lines.add(welcomeFullRow("No recent activity", inner));
        } else {
            appendRecentRows(lines, inner, Math.min(2, recentConversations.size()));
        }
        lines.add(ORANGE + "╰" + "─".repeat(panelWidth - 2) + "╯" + RESET);
        return lines;
    }

    private String welcomeColumns(String left, String right, int leftWidth, int rightWidth) {
        return ORANGE + "│" + RESET + " " + padRight(clip(left, leftWidth - 2), leftWidth - 2) + " "
                + ORANGE + "│" + RESET + " " + padRight(clip(right, rightWidth - 2), rightWidth - 2) + " "
                + ORANGE + "│" + RESET;
    }

    private String welcomeFullRow(String text, int innerWidth) {
        return ORANGE + "│" + RESET + " " + padRight(clip(text, innerWidth - 2), innerWidth - 2)
                + " " + ORANGE + "│" + RESET;
    }

    private void appendRecentRows(List<String> lines, int innerWidth, int limit) {
        if (limit == 0) {
            lines.add(welcomeFullRow("No recent activity", innerWidth));
            return;
        }
        for (int i = 0; i < limit; i++) {
            RunRecord run = recentConversations.get(i);
            String marker = switch (run.getStatus()) {
                case SUCCEEDED -> "●";
                case FAILED -> "!";
                default -> "○";
            };
            lines.add(welcomeFullRow(marker + " " + compact(run.getTask(), innerWidth - 8), innerWidth));
        }
    }

    private String center(String value, int width) {
        int padding = Math.max(0, (width - displayWidth(stripAnsi(value))) / 2);
        return pad(padding) + value;
    }

    private List<String> renderTranscript(int width) {
        List<String> lines = new ArrayList<>();
        for (CliTranscriptModel.Block block : transcript.blocks()) {
            if (block instanceof CliTranscriptModel.TextBlock text) {
                lines.add("");
                String prefix = switch (text.kind()) {
                    case USER -> "❯ "; case ASSISTANT -> "● "; case ERROR -> "! "; default -> "  ";
                    case TOOL -> "  ";
                };
                String color = switch (text.kind()) {
                    case USER -> USER_BG; case ERROR -> RED; case SYSTEM -> DIM; default -> "";
                };
                List<String> wrapped = wrap(text.text(), Math.max(10, width - 3));
                for (int i = 0; i < wrapped.size(); i++) {
                    String value = (i == 0 ? prefix : "  ") + wrapped.get(i);
                    if (text.kind() == CliTranscriptModel.Kind.USER) value = padRight(value, width);
                    lines.add(color + value + RESET);
                }
            } else if (block instanceof CliTranscriptModel.ToolBlock tool) {
                boolean selected = toolFocus && selectedTool == tool.index();
                String disclosure = tool.expanded() ? "▾" : "▸";
                String stateColor = tool.error() ? RED : "running".equals(tool.status()) ? CYAN : GREEN;
                String duration = tool.durationMs() > 0 ? " " + formatDuration(tool.durationMs()) : "";
                lines.add((selected ? USER_BG : "") + stateColor + disclosure + " " + tool.name() + RESET
                        + DIM + " · " + tool.status() + duration + "  [" + tool.index() + "]" + RESET);
                if (tool.expanded()) {
                    appendToolDetail(lines, "input", tool.request(), width);
                    appendToolDetail(lines, "output", tool.output(), width);
                }
            }
        }
        return lines;
    }

    private void appendToolDetail(List<String> lines, String label, String value, int width) {
        if (value == null || value.isBlank()) return;
        lines.add(DIM + "  " + label + RESET);
        List<String> wrapped = wrap(value, Math.max(10, width - 6));
        int limit = Math.min(20, wrapped.size());
        for (int i = 0; i < limit; i++) lines.add("    " + wrapped.get(i));
        if (wrapped.size() > limit) lines.add(DIM + "    … " + (wrapped.size() - limit) + " more lines" + RESET);
    }

    private List<String> renderSuggestions(List<Suggestion> suggestions, int width) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(8, suggestions.size()); i++) {
            Suggestion item = suggestions.get(i);
            String prefix = i == menuIndex ? ORANGE + "› " : "  ";
            lines.add(prefix + String.format("%-20s", item.value) + RESET + DIM + clip(item.description, width - 24) + RESET);
        }
        return lines;
    }

    private List<String> renderResumePicker(int width) {
        List<String> lines = new ArrayList<>();
        lines.add(ORANGE + " Resume conversation" + RESET + DIM + "  j/k select · Enter resume · Esc close" + RESET);
        int limit = Math.min(9, resumeChoices.size());
        for (int i = 0; i < limit; i++) {
            RunRecord run = resumeChoices.get(i);
            lines.add((i == resumeIndex ? USER_BG + ORANGE + "› " : "  ")
                    + (i + 1) + "  " + run.getStatus() + "  " + compact(run.getTask(), width - 20) + RESET);
        }
        return lines;
    }

    private String activityText() {
        if (confirmExit) return "Exit JobClaw? " + (activeRun == null ? "" : "The current task will stop. ") + "[y/N]";
        if (runtimeError != null) return "Runtime unavailable: " + runtimeError;
        if (context == null) return "Loading runtime " + elapsed();
        if (activeRun != null) return transcript.status() + " · " + elapsed();
        return transcript.status();
    }

    private String spinner() {
        String[] frames = {"◐", "◓", "◑", "◒"};
        return frames[(int) ((System.nanoTime() / 150_000_000L) % frames.length)];
    }

    private String elapsed() {
        return formatDuration(Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    private String formatDuration(long millis) {
        return millis < 1_000 ? millis + "ms" : String.format(Locale.ROOT, "%.1fs", millis / 1_000.0);
    }

    private List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return List.of("");
        for (String source : text.replace("\r", "").split("\n", -1)) {
            if (source.isEmpty()) { lines.add(""); continue; }
            StringBuilder line = new StringBuilder();
            int used = 0;
            for (int offset = 0; offset < source.length();) {
                int cp = source.codePointAt(offset);
                int size = codePointWidth(cp);
                if (used + size > width && !line.isEmpty()) {
                    lines.add(line.toString()); line.setLength(0); used = 0;
                }
                line.appendCodePoint(cp); used += size; offset += Character.charCount(cp);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private String clip(String text, int width) {
        if (text == null || width <= 0) return "";
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            int size = codePointWidth(cp);
            if (used + size > width) break;
            result.appendCodePoint(cp); used += size; offset += Character.charCount(cp);
        }
        return result.toString();
    }

    private ComposerView composerView(int availableWidth) {
        int safeCursor = Math.max(0, Math.min(cursor, composer.length()));
        String before = composer.substring(0, safeCursor);
        String after = composer.substring(safeCursor);
        int width = Math.max(1, availableWidth);

        int afterBudget = Math.min(displayWidth(after), Math.max(0, width / 3));
        String visibleAfter = clip(after, afterBudget);
        int beforeBudget = Math.max(0, width - displayWidth(visibleAfter));
        boolean clippedLeft = displayWidth(before) > beforeBudget;
        String visibleBefore = clipTail(before, clippedLeft ? Math.max(0, beforeBudget - 1) : beforeBudget);
        if (clippedLeft) {
            visibleBefore = "‹" + visibleBefore;
        }
        return new ComposerView(visibleBefore + visibleAfter, displayWidth(visibleBefore));
    }

    private String clipTail(String text, int width) {
        if (text == null || text.isEmpty() || width <= 0) return "";
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = text.length(); offset > 0;) {
            int cp = text.codePointBefore(offset);
            int size = codePointWidth(cp);
            if (used + size > width) break;
            result.insert(0, Character.toChars(cp));
            used += size;
            offset -= Character.charCount(cp);
        }
        return result.toString();
    }

    private String clipAnsi(String text, int width) {
        if (text == null || width <= 0) return "";
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < text.length();) {
            if (text.charAt(offset) == '\033' && offset + 1 < text.length() && text.charAt(offset + 1) == '[') {
                int end = offset + 2;
                while (end < text.length() && !(text.charAt(end) >= '@' && text.charAt(end) <= '~')) end++;
                if (end < text.length()) end++;
                result.append(text, offset, end);
                offset = end;
                continue;
            }
            int cp = text.codePointAt(offset);
            int size = codePointWidth(cp);
            if (used + size > width) break;
            result.appendCodePoint(cp);
            used += size;
            offset += Character.charCount(cp);
        }
        return result.append(RESET).toString();
    }

    private String stripAnsi(String value) {
        return value == null ? "" : ANSI_PATTERN.matcher(value).replaceAll("");
    }

    private String padRight(String text, int width) {
        return text + pad(Math.max(0, width - displayWidth(text)));
    }

    private String pad(int count) {
        return " ".repeat(Math.max(0, count));
    }

    private int displayWidth(String text) {
        if (text == null) return 0;
        int width = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            width += codePointWidth(cp);
            offset += Character.charCount(cp);
        }
        return width;
    }

    private int codePointWidth(int cp) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES ? 2 : 1;
    }

    private void setComposer(String value) {
        composer.setLength(0);
        composer.append(value == null ? "" : value);
        cursor = composer.length();
        resetMenuSelection();
        dirty = true;
    }

    private void resetMenuSelection() {
        menuIndex = 0;
        historyIndex = -1;
    }

    private void loadHistory() {
        try {
            Path path = historyPath();
            if (Files.exists(path)) history.addAll(Files.readAllLines(path, StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    private void addHistory(String input) {
        history.add(input);
        try {
            Path path = historyPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, input.replace('\n', ' ') + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    private Path historyPath() {
        return Path.of(System.getProperty("user.home"), ".jobclaw", "cli-history");
    }

    private String compact(String text, int max) {
        if (text == null) return "";
        String value = text.replace('\r', ' ').replace('\n', ' ').trim();
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    private record Command(String name, String description) { }
    private record Suggestion(String value, String description) { }
    private record ComposerView(String text, int cursorOffset) { }
}
