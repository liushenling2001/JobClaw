package io.jobclaw.cli;

import io.jobclaw.run.RunRecord;
import io.jobclaw.run.RunService;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class ResumeCommand extends CliCommand {
    private static final DateTimeFormatter RUN_TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final RunService runService;

    public ResumeCommand(RunService runService) {
        this.runService = runService;
    }

    @Override
    public String name() {
        return "resume";
    }

    @Override
    public String description() {
        return "Show recent conversations or attach to a previous run";
    }

    @Override
    public int execute(String[] args) throws Exception {
        if (args.length == 0) {
            List<RunRecord> runs = runService.listRuns(20);
            if (runs.isEmpty()) {
                System.out.println("No recent conversations");
                return 0;
            }
            System.out.println("Recent conversations:");
            for (int i = 0; i < runs.size(); i++) {
                System.out.println(formatRunChoice(i + 1, runs.get(i)));
            }
            System.out.println();
            System.out.println("Use: jobclaw resume <runId>");
            return 0;
        }
        RunRecord record = runService.getRequired(args[0]);
        System.out.println("attached " + record.getRunId() + " " + record.getStatus());
        System.out.println("session  " + record.getSessionKey());
        System.out.println("project  " + record.getProjectRoot());
        System.out.println("task     " + summarize(record.getTask()));
        if (record.getFinalResponse() != null && !record.getFinalResponse().isBlank()) {
            System.out.println();
            System.out.println("last response:");
            System.out.println(summarize(record.getFinalResponse()));
        }
        System.out.println();
        return record.getExitCode() != null ? record.getExitCode() : 0;
    }

    @Override
    public void printHelp() {
        System.out.println("jobclaw resume [runId]");
        System.out.println("  without runId, show recent conversations");
    }

    private String formatRunChoice(int index, RunRecord run) {
        String time = run.getUpdatedAt() != null ? RUN_TIME_FORMAT.format(run.getUpdatedAt()) : "--";
        return "%2d. %s  %-9s  %s  %s".formatted(
                index,
                time,
                run.getStatus(),
                run.getRunId(),
                summarize(run.getTask())
        );
    }

    private String summarize(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replace("\r", " ").replace("\n", " ").trim();
        return compact.length() > 120 ? compact.substring(0, 120) + "..." : compact;
    }
}
