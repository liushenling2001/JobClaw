package io.jobclaw.cli;

import io.jobclaw.run.RunRecord;
import io.jobclaw.run.RunService;
import org.springframework.stereotype.Component;

@Component
public class RunsCommand extends CliCommand {
    private final RunService runService;

    public RunsCommand(RunService runService) {
        this.runService = runService;
    }

    @Override
    public String name() {
        return "runs";
    }

    @Override
    public String description() {
        return "List recent JobClaw runs";
    }

    @Override
    public int execute(String[] args) throws Exception {
        int limit = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        for (RunRecord run : runService.listRuns(limit)) {
            System.out.println(run.getRunId() + "  " + run.getStatus() + "  " + summarize(run.getTask()));
        }
        return 0;
    }

    @Override
    public void printHelp() {
        System.out.println("jobclaw runs [limit]");
    }

    private String summarize(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replace("\r", " ").replace("\n", " ").trim();
        return compact.length() > 120 ? compact.substring(0, 120) + "..." : compact;
    }
}
