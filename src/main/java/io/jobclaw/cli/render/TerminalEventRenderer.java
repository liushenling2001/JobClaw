package io.jobclaw.cli.render;

import io.jobclaw.agent.ExecutionEvent;

public class TerminalEventRenderer {
    private static final String RESET = "\033[0m";
    private static final String DIM = "\033[2m";
    private static final String USER_BG = "\033[48;5;238m";
    private final boolean ansi;
    private final boolean workingInputBox;
    private boolean statusActive;
    private boolean boxActive;
    private int toolCount;

    public TerminalEventRenderer() {
        this(System.console() != null, false);
    }

    public TerminalEventRenderer(boolean ansi) {
        this(ansi, false);
    }

    public TerminalEventRenderer(boolean ansi, boolean workingInputBox) {
        this.ansi = ansi;
        this.workingInputBox = workingInputBox;
    }

    public void renderUser(String task) {
        clearWorkingArea();
        String line = "> " + (task == null ? "" : task.trim());
        System.out.println();
        System.out.println(color(USER_BG, padRight(line, terminalWidth())));
        if (workingInputBox) {
            renderWorkingInputBox("");
        }
    }

    public void renderRunSummary(String status, String runId) {
        clearWorkingArea();
        System.out.println();
        System.out.println(dim("run " + runId + " " + status));
    }

    public void renderArtifacts(Iterable<String> paths) {
        clearWorkingArea();
        System.out.println();
        System.out.println(dim("artifacts"));
        for (String path : paths) {
            System.out.println(dim("  " + path));
        }
    }

    public void render(ExecutionEvent event) {
        if (event == null) {
            return;
        }
        switch (event.getType()) {
            case THINK_START -> {
                status("thinking...");
            }
            case THINK_STREAM -> {
                // Stream chunks are persisted to run events; the terminal prints the final response once.
            }
            case THINK_END -> {
                status("");
            }
            case TOOL_START -> {
                status(toolName(event) + " running");
            }
            case TOOL_END -> {
                toolCount++;
                status("tool #" + toolCount + " " + toolName(event) + " done" + durationSuffix(event)
                        + "  (/tool last " + toolCount + " to expand)");
            }
            case TOOL_ERROR, ERROR -> {
                clearWorkingArea();
                System.out.println();
                System.out.println("! " + compact(event.getContent()));
            }
            case TOOL_OUTPUT -> {
                // Keep command output discoverable through `jobclaw logs`; avoid flooding the live CLI.
            }
            case FINAL_RESPONSE -> {
                clearWorkingArea();
                if (!event.getContent().isBlank()) {
                    renderAssistant(event.getContent());
                }
            }
            case CUSTOM -> {
                if ("tool_progress".equals(String.valueOf(event.getMetadata().get("label")))) {
                    status(toolName(event) + " still running" + durationSuffix(event));
                }
            }
            default -> {
                // Unknown events are intentionally quiet in the interactive terminal.
            }
        }
    }

    private void renderAssistant(String content) {
        System.out.println();
        String[] lines = content.strip().split("\\R", -1);
        if (lines.length == 0) {
            return;
        }
        System.out.println("• " + lines[0]);
        for (int i = 1; i < lines.length; i++) {
            System.out.println("  " + lines[i]);
        }
    }

    private void status(String text) {
        String value = dim("  " + text);
        if (workingInputBox && boxActive && ansi) {
            System.out.print("\033[4A\r\033[2K" + value + "\033[4B\r");
            System.out.flush();
            statusActive = true;
            return;
        }
        if (ansi) {
            System.out.print("\r\033[2K" + value);
            System.out.flush();
            statusActive = true;
            return;
        }
        System.out.println(value);
    }

    private void clearWorkingArea() {
        if (workingInputBox && boxActive && ansi) {
            System.out.print("\033[4A");
            for (int i = 0; i < 4; i++) {
                System.out.print("\r\033[2K");
                if (i < 3) {
                    System.out.print("\033[1B");
                }
            }
            System.out.print("\r");
            System.out.flush();
            boxActive = false;
            statusActive = false;
            return;
        }
        if (ansi && statusActive) {
            System.out.print("\r\033[2K");
            System.out.flush();
            statusActive = false;
        }
    }

    private void renderWorkingInputBox(String status) {
        int width = terminalWidth();
        System.out.println(dim(status == null ? "" : status));
        System.out.println("─".repeat(width));
        System.out.println("> ");
        System.out.println("─".repeat(width));
        boxActive = ansi;
    }

    private String toolName(ExecutionEvent event) {
        Object tool = event.getMetadata().get("toolName");
        if (tool == null) {
            tool = event.getMetadata().get("tool");
        }
        return tool != null ? String.valueOf(tool) : compact(event.getContent());
    }

    private String durationSuffix(ExecutionEvent event) {
        Object duration = event.getMetadata().get("durationMs");
        if (!(duration instanceof Number number)) {
            return "";
        }
        long ms = number.longValue();
        if (ms < 1000) {
            return " " + ms + "ms";
        }
        return " " + String.format("%.1fs", ms / 1000.0);
    }

    private String compact(String content) {
        if (content == null) {
            return "";
        }
        String compact = content.replace("\r", " ").replace("\n", " ").trim();
        return compact.length() > 240 ? compact.substring(0, 240) + "..." : compact;
    }

    private String dim(String text) {
        return color(DIM, text);
    }

    private String color(String code, String text) {
        return ansi ? code + text + RESET : text;
    }

    private String padRight(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        return value + " ".repeat(width - value.length());
    }

    private int terminalWidth() {
        String columns = System.getenv("COLUMNS");
        if (columns != null) {
            try {
                return Math.max(20, Math.min(160, Integer.parseInt(columns)));
            } catch (NumberFormatException ignored) {
                // Fall through to the default width.
            }
        }
        return 100;
    }
}
