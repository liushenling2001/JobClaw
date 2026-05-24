package io.jobclaw.cli;

import io.jobclaw.cli.render.TerminalEventRenderer;
import io.jobclaw.run.RunRecord;
import io.jobclaw.run.RunRequest;
import io.jobclaw.run.RunService;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class RunCommand extends CliCommand {
    private final RunService runService;

    public RunCommand(RunService runService) {
        this.runService = runService;
    }

    @Override
    public String name() {
        return "run";
    }

    @Override
    public String description() {
        return "Run an agentic task in the current project";
    }

    @Override
    public int execute(String[] args) throws Exception {
        return execute(args, true);
    }

    public int executeTask(String task) throws Exception {
        return executeTask(task, true);
    }

    public int executeTask(String task, boolean printSummary) throws Exception {
        return execute(new String[]{task}, printSummary, true, null);
    }

    public int executeTask(String task, boolean printSummary, boolean renderUser, String session) throws Exception {
        return execute(new String[]{task}, printSummary, renderUser, session);
    }

    private int execute(String[] args, boolean printSummary) throws Exception {
        return execute(args, printSummary, true, null);
    }

    private int execute(String[] args, boolean printSummary, boolean renderUser, String sessionOverride) throws Exception {
        ParsedArgs parsed = parse(args);
        if (parsed.task == null || parsed.task.isBlank()) {
            printHelp();
            return 1;
        }
        if (sessionOverride != null && !sessionOverride.isBlank()) {
            parsed.session = sessionOverride;
        }
        TerminalEventRenderer renderer = new TerminalEventRenderer();
        if (renderUser) {
            renderer.renderUser(parsed.task);
        }
        RunRecord record = runService.startForeground(new RunRequest(
                parsed.task,
                parsed.session,
                parsed.cwd,
                parsed.cwd,
                "cli",
                parsed.approvalMode,
                parsed.sandboxMode,
                null
        ), renderer::render);
        if (printSummary) {
            renderer.renderRunSummary(statusPrefix(record), record.getRunId());
            if (record.getArtifactPaths() != null && !record.getArtifactPaths().isEmpty()) {
                renderer.renderArtifacts(record.getArtifactPaths());
            }
        }
        return record.getExitCode() != null ? record.getExitCode() : 0;
    }

    private String statusPrefix(RunRecord record) {
        return switch (record.getStatus()) {
            case SUCCEEDED -> "done";
            case FAILED -> "failed";
            case CANCELLED -> "cancelled";
            default -> record.getStatus().name().toLowerCase();
        };
    }

    private ParsedArgs parse(String[] args) throws Exception {
        ParsedArgs parsed = new ParsedArgs();
        List<String> taskParts = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--file" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--file requires a path");
                    }
                    parsed.task = Files.readString(Path.of(args[++i]));
                }
                case "--session", "-s" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--session requires a value");
                    }
                    parsed.session = args[++i];
                }
                case "--cwd" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--cwd requires a path");
                    }
                    parsed.cwd = args[++i];
                }
                case "--approval" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--approval requires a value");
                    }
                    parsed.approvalMode = args[++i];
                }
                case "--sandbox" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--sandbox requires a value");
                    }
                    parsed.sandboxMode = args[++i];
                }
                default -> taskParts.add(arg);
            }
        }
        if ((parsed.task == null || parsed.task.isBlank()) && !taskParts.isEmpty()) {
            parsed.task = String.join(" ", taskParts);
        }
        return parsed;
    }

    @Override
    public void printHelp() {
        System.out.println("jobclaw run \"task\"");
        System.out.println("  --file <path>       Read task from file");
        System.out.println("  --session <key>     Use a session key");
        System.out.println("  --cwd <path>        Project working directory");
        System.out.println("  --approval <mode>   ask|auto|readonly|suggest");
    }

    private static class ParsedArgs {
        String task;
        String session;
        String cwd;
        String approvalMode = "ask";
        String sandboxMode = "workspace-write";
    }
}
