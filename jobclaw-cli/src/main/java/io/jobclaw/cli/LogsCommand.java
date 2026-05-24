package io.jobclaw.cli;

import io.jobclaw.run.RunService;
import org.springframework.stereotype.Component;

@Component
public class LogsCommand extends CliCommand {
    private final RunService runService;

    public LogsCommand(RunService runService) {
        this.runService = runService;
    }

    @Override
    public String name() {
        return "logs";
    }

    @Override
    public String description() {
        return "Show persisted run events";
    }

    @Override
    public int execute(String[] args) throws Exception {
        if (args.length == 0) {
            printHelp();
            return 1;
        }
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 120;
        for (var event : runService.readEvents(args[0], limit)) {
            System.out.println(event.getTimestamp() + " " + event.getType() + " " + summarize(event.getContent()));
        }
        return 0;
    }

    @Override
    public void printHelp() {
        System.out.println("jobclaw logs <runId> [limit]");
    }

    private String summarize(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replace("\r", " ").replace("\n", " ").trim();
        return compact.length() > 300 ? compact.substring(0, 300) + "..." : compact;
    }
}
