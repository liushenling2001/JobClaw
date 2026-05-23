package io.jobclaw.cli.render;

import io.jobclaw.agent.ExecutionEvent;

public class TerminalEventRenderer {
    private static final String RESET = "\033[0m";
    private static final String DIM = "\033[2m";
    private static final String USER_BG = "\033[48;5;238m";
    private final boolean ansi;
    private boolean statusActive;

    public TerminalEventRenderer() {
        this(System.console() != null);
    }

    public TerminalEventRenderer(boolean ansi) {
        this.ansi = ansi;
    }

    public void renderUser(String task) {
        clearStatus();
        String line = "> " + (task == null ? "" : task.trim());
        System.out.println();
        System.out.println(color(USER_BG, padRight(line, terminalWidth())));
    }

    public void renderRunSummary(String status, String runId) {
        clearStatus();
        System.out.println();
        System.out.println(dim("run " + runId + " " + status));
    }

    public void renderArtifacts(Iterable<String> paths) {
        clearStatus();
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
                clearStatus();
            }
            case TOOL_START -> {
                status(toolName(event) + " running");
            }
            case TOOL_END -> {
                status(toolName(event) + " done" + durationSuffix(event));
            }
            case TOOL_ERROR, ERROR -> {
                clearStatus();
                System.out.println();
                System.out.println("! " + compact(event.getContent()));
            }
            case TOOL_OUTPUT -> {
                // Keep command output discoverable through `jobclaw logs`; avoid flooding the live CLI.
            }
            case FINAL_RESPONSE -> {
                clearStatus();
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
        if (ansi) {
            System.out.print("\r\033[2K" + value);
            System.out.flush();
            statusActive = true;
            return;
        }
        System.out.println(value);
    }

    private void clearStatus() {
        if (ansi && statusActive) {
            System.out.print("\r\033[2K");
            System.out.flush();
            statusActive = false;
        }
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
