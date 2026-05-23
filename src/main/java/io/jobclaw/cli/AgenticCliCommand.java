package io.jobclaw.cli;

import io.jobclaw.run.RunRecord;
import io.jobclaw.run.RunService;
import io.jobclaw.workspace.WorkspaceContext;
import io.jobclaw.workspace.WorkspaceInspector;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class AgenticCliCommand extends CliCommand {
    private final ObjectProvider<RunCommand> runCommand;
    private final ObjectProvider<RunService> runService;
    private final WorkspaceInspector workspaceInspector;

    public AgenticCliCommand(ObjectProvider<RunCommand> runCommand,
                             ObjectProvider<RunService> runService,
                             WorkspaceInspector workspaceInspector) {
        this.runCommand = runCommand;
        this.runService = runService;
        this.workspaceInspector = workspaceInspector;
    }

    @Override
    public String name() {
        return "shell";
    }

    @Override
    public String description() {
        return "Open the JobClaw agentic CLI";
    }

    @Override
    public int execute(String[] args) throws Exception {
        WorkspaceContext workspace = workspaceInspector.inspect(null);
        try (Terminal terminal = TerminalBuilder.builder()
                .name("jobclaw")
                .system(true)
                .build()) {
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .appName("jobclaw")
                    .completer(new StringsCompleter(List.of(
                            "/status", "/runs", "/attach", "/logs", "/artifacts", "/resume", "/help", "/exit", "/quit"
                    )))
                    .variable(LineReader.HISTORY_FILE, historyFile())
                    .build();

            printWelcome(terminal, workspace);
            while (true) {
                String input;
                try {
                    input = reader.readLine("jobclaw ❯ ").trim();
                } catch (UserInterruptException e) {
                    terminal.writer().println();
                    continue;
                } catch (EndOfFileException e) {
                    terminal.writer().println();
                    return 0;
                }
                if (input.isBlank()) {
                    continue;
                }
                if (input.startsWith("/")) {
                    if (handleSlash(input, terminal)) {
                        return 0;
                    }
                    continue;
                }
                runCommand.getObject().executeTask(input, false);
                terminal.writer().println();
                terminal.flush();
            }
        }
    }

    private boolean handleSlash(String input, Terminal terminal) throws Exception {
        String[] parts = input.split("\\s+", 2);
        String command = parts[0];
        String rest = parts.length > 1 ? parts[1].trim() : "";
        switch (command) {
            case "/exit", "/quit" -> {
                return true;
            }
            case "/help" -> {
                printShellHelp(terminal);
                return false;
            }
            case "/status" -> {
                printCommandSection("runs", terminal);
                new RunsPrinter(runService.getObject()).printRuns(10);
                return false;
            }
            case "/runs" -> {
                printCommandSection("runs", terminal);
                new RunsPrinter(runService.getObject()).printRuns(20);
                return false;
            }
            case "/logs" -> {
                printCommandSection("logs", terminal);
                new RunsPrinter(runService.getObject()).printLogs(rest, 80);
                return false;
            }
            case "/attach" -> {
                printCommandSection("attach", terminal);
                if (rest.isBlank()) {
                    System.out.println("usage: /attach <runId>");
                } else {
                    new AttachCommand(runService.getObject()).execute(new String[]{rest});
                }
                return false;
            }
            case "/artifacts" -> {
                printCommandSection("artifacts", terminal);
                new RunsPrinter(runService.getObject()).printArtifacts(rest);
                return false;
            }
            case "/resume" -> {
                printCommandSection("resume", terminal);
                if (rest.isBlank()) {
                    System.out.println("usage: /resume <runId>");
                } else {
                    new ResumeCommand(runService.getObject()).execute(new String[]{rest});
                }
                return false;
            }
            default -> {
                System.out.println("unknown command: " + command);
                return false;
            }
        }
    }

    @Override
    public void printHelp() {
        System.out.println("jobclaw");
        System.out.println("Open the interactive agentic CLI.");
    }

    private String display(String value) {
        return value != null && !value.isBlank() ? value : "no-git";
    }

    private Path historyFile() throws Exception {
        Path path = Path.of(System.getProperty("user.home"), ".jobclaw", "history", "shell.history");
        Files.createDirectories(path.getParent());
        return path;
    }

    private void printWelcome(Terminal terminal, WorkspaceContext workspace) {
        int width = 76;
        String border = "+" + "-".repeat(width - 2) + "+";
        if (System.console() != null) {
            terminal.writer().println("\033[2J\033[H");
        }
        terminal.writer().println(border);
        terminal.writer().println(row(width, "JobClaw CLI"));
        terminal.writer().println(row(width, ""));
        terminal.writer().println(row(width, "project  " + workspace.projectRoot()));
        terminal.writer().println(row(width, "branch   " + display(workspace.gitBranch())));
        terminal.writer().println(row(width, ""));
        terminal.writer().println(row(width, "Type a task to run it in this workspace. Use /help for commands."));
        terminal.writer().println(row(width, "/status  /runs  /attach <runId>  /logs <runId>  /resume <runId>"));
        terminal.writer().println(row(width, "/artifacts <runId>  /exit"));
        terminal.writer().println(border);
        terminal.writer().println();
        terminal.flush();
    }

    private void printShellHelp(Terminal terminal) {
        printCommandSection("help", terminal);
        terminal.writer().println("/status                 show recent runs");
        terminal.writer().println("/runs                   show more recent runs");
        terminal.writer().println("/attach <runId>         replay a run");
        terminal.writer().println("/logs <runId>           show persisted run events");
        terminal.writer().println("/artifacts <runId>      list run artifacts");
        terminal.writer().println("/resume <runId>         resume a run");
        terminal.writer().println("/exit                   leave the shell");
        terminal.flush();
    }

    private void printCommandSection(String name, Terminal terminal) {
        terminal.writer().println();
        terminal.writer().println("----- " + name + " " + "-".repeat(Math.max(1, 62 - name.length())));
        terminal.flush();
    }

    private String row(int width, String text) {
        String value = shorten(text, width - 4);
        return "| " + value + " ".repeat(width - 4 - value.length()) + " |";
    }

    private String shorten(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        if (maxLength <= 3) {
            return text.substring(0, maxLength);
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    private record RunsPrinter(RunService runService) {
        void printRuns(int limit) throws Exception {
            for (RunRecord run : runService.listRuns(limit)) {
                System.out.println(run.getRunId() + "  " + run.getStatus() + "  " + summarize(run.getTask()));
            }
        }

        void printLogs(String runId, int limit) throws Exception {
            if (runId == null || runId.isBlank()) {
                System.out.println("usage: /logs <runId>");
                return;
            }
            runService.readEvents(runId, limit).forEach(event ->
                    System.out.println(event.getTimestamp() + " " + event.getType() + " " + summarize(event.getContent())));
        }

        void printArtifacts(String runId) throws Exception {
            if (runId == null || runId.isBlank()) {
                System.out.println("usage: /artifacts <runId>");
                return;
            }
            var artifacts = runService.readArtifacts(runId);
            if (artifacts.isEmpty()) {
                System.out.println("(no artifacts indexed)");
                return;
            }
            artifacts.forEach(path -> System.out.println(path));
        }

        private String summarize(String text) {
            if (text == null) {
                return "";
            }
            String compact = text.replace("\r", " ").replace("\n", " ").trim();
            return compact.length() > 100 ? compact.substring(0, 100) + "..." : compact;
        }
    }
}
