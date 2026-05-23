package io.jobclaw.cli;

import io.jobclaw.run.RunService;
import org.springframework.stereotype.Component;

@Component
public class ArtifactsCommand extends CliCommand {
    private final RunService runService;

    public ArtifactsCommand(RunService runService) {
        this.runService = runService;
    }

    @Override
    public String name() {
        return "artifacts";
    }

    @Override
    public String description() {
        return "List artifacts indexed for a run";
    }

    @Override
    public int execute(String[] args) throws Exception {
        if (args.length == 0) {
            printHelp();
            return 1;
        }
        var artifacts = runService.readArtifacts(args[0]);
        if (artifacts.isEmpty()) {
            System.out.println("(no artifacts indexed)");
            return 0;
        }
        artifacts.forEach(System.out::println);
        return 0;
    }

    @Override
    public void printHelp() {
        System.out.println("jobclaw artifacts <runId>");
    }
}
