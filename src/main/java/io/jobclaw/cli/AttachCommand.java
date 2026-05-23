package io.jobclaw.cli;

import io.jobclaw.cli.render.TerminalEventRenderer;
import io.jobclaw.run.RunRecord;
import io.jobclaw.run.RunService;
import org.springframework.stereotype.Component;

@Component
public class AttachCommand extends CliCommand {
    private final RunService runService;

    public AttachCommand(RunService runService) {
        this.runService = runService;
    }

    @Override
    public String name() {
        return "attach";
    }

    @Override
    public String description() {
        return "Replay a run's execution events";
    }

    @Override
    public int execute(String[] args) throws Exception {
        if (args.length == 0) {
            printHelp();
            return 1;
        }
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        RunRecord run = runService.getRequired(args[0]);
        System.out.println("run " + run.getRunId() + " " + run.getStatus());
        System.out.println("project " + run.getProjectRoot());
        System.out.println();
        TerminalEventRenderer renderer = new TerminalEventRenderer();
        runService.readEvents(args[0], limit).forEach(renderer::render);
        return run.getExitCode() != null ? run.getExitCode() : 0;
    }

    @Override
    public void printHelp() {
        System.out.println("jobclaw attach <runId> [eventLimit]");
    }
}
