package io.jobclaw.cli;

import io.jobclaw.cli.render.TerminalEventRenderer;
import io.jobclaw.run.RunRecord;
import io.jobclaw.run.RunService;
import org.springframework.stereotype.Component;

@Component
public class ResumeCommand extends CliCommand {
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
        return "Resume a previous run in a new run";
    }

    @Override
    public int execute(String[] args) throws Exception {
        if (args.length == 0) {
            printHelp();
            return 1;
        }
        TerminalEventRenderer renderer = new TerminalEventRenderer();
        RunRecord record = runService.resumeForeground(args[0], renderer::render);
        System.out.println();
        System.out.println("run " + record.getRunId() + " " + record.getStatus());
        return record.getExitCode() != null ? record.getExitCode() : 0;
    }

    @Override
    public void printHelp() {
        System.out.println("jobclaw resume <runId>");
    }
}
