package io.jobclaw.cli;

import org.springframework.stereotype.Component;

/**
 * Version command - show version info.
 */
@Component
public class VersionCommand extends CliCommand {

    @Override
    public String name() {
        return "version";
    }

    @Override
    public String description() {
        return "Show version";
    }

    @Override
    public int execute(String[] args) {
        System.out.println(LOGO + " JobClaw v" + VERSION);
        return 0;
    }

    @Override
    public void printHelp() {
        System.out.println(LOGO + " jobclaw version - Show version info");
        System.out.println();
        System.out.println("Usage: jobclaw version");
    }
}
