package io.jobclaw.cli.render;

import io.jobclaw.agent.ExecutionEvent;

public class TerminalEventRenderer {
    private boolean thinking;
    private String activeSection;

    public void renderUser(String task) {
        section("user");
        System.out.println(task == null ? "" : task.trim());
    }

    public void renderRunSummary(String status, String runId) {
        section("run");
        System.out.println(status + " " + runId);
    }

    public void renderArtifacts(Iterable<String> paths) {
        section("artifacts");
        for (String path : paths) {
            System.out.println(path);
        }
    }

    public void render(ExecutionEvent event) {
        if (event == null) {
            return;
        }
        switch (event.getType()) {
            case THINK_START -> {
                thinking = true;
                section("assistant");
                System.out.println("thinking...");
            }
            case THINK_STREAM -> {
                // Stream chunks are persisted to run events; the terminal prints the final response once.
            }
            case THINK_END -> {
                thinking = false;
            }
            case TOOL_START -> {
                clearThinkingLine();
                section("tool");
                System.out.println("start " + toolName(event));
            }
            case TOOL_END -> {
                clearThinkingLine();
                section("tool");
                System.out.println("done  " + toolName(event));
            }
            case TOOL_ERROR, ERROR -> {
                clearThinkingLine();
                section("error");
                System.out.println(compact(event.getContent()));
            }
            case TOOL_OUTPUT -> {
                // Keep command output discoverable through `jobclaw logs`; avoid flooding the live CLI.
            }
            case FINAL_RESPONSE -> {
                clearThinkingLine();
                if (!event.getContent().isBlank()) {
                    section("assistant");
                    System.out.println(event.getContent());
                }
            }
            default -> {
                // Unknown events are intentionally quiet in the interactive terminal.
            }
        }
    }

    private void clearThinkingLine() {
        thinking = false;
    }

    private void section(String name) {
        if (name.equals(activeSection) && !"tool".equals(name)) {
            return;
        }
        activeSection = name;
        System.out.println();
        System.out.println("----- " + name + " " + "-".repeat(Math.max(1, 62 - name.length())));
    }

    private String toolName(ExecutionEvent event) {
        Object tool = event.getMetadata().get("toolName");
        if (tool == null) {
            tool = event.getMetadata().get("tool");
        }
        return tool != null ? String.valueOf(tool) : compact(event.getContent());
    }

    private String compact(String content) {
        if (content == null) {
            return "";
        }
        String compact = content.replace("\r", " ").replace("\n", " ").trim();
        return compact.length() > 240 ? compact.substring(0, 240) + "..." : compact;
    }
}
