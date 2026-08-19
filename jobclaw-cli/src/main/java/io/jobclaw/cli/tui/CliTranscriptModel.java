package io.jobclaw.cli.tui;

import io.jobclaw.agent.ExecutionEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mutable transcript state. Only the TUI event-loop thread may mutate it. */
final class CliTranscriptModel {
    enum Kind { USER, ASSISTANT, TOOL, SYSTEM, ERROR }

    sealed interface Block permits TextBlock, ToolBlock {
        Kind kind();
    }

    static final class TextBlock implements Block {
        private final Kind kind;
        private final StringBuilder text = new StringBuilder();

        TextBlock(Kind kind, String text) {
            this.kind = kind;
            append(text);
        }

        @Override
        public Kind kind() {
            return kind;
        }

        String text() {
            return text.toString();
        }

        void append(String delta) {
            if (delta != null) {
                text.append(delta);
            }
        }
    }

    static final class ToolBlock implements Block {
        private final int index;
        private final String id;
        private final String name;
        private final String request;
        private final StringBuilder output = new StringBuilder();
        private String status = "running";
        private long durationMs;
        private boolean expanded;
        private boolean error;

        ToolBlock(int index, String id, String name, String request) {
            this.index = index;
            this.id = id;
            this.name = name;
            this.request = request;
        }

        @Override
        public Kind kind() {
            return Kind.TOOL;
        }

        int index() { return index; }
        String id() { return id; }
        String name() { return name; }
        String request() { return request; }
        String output() { return output.toString(); }
        String status() { return status; }
        long durationMs() { return durationMs; }
        boolean expanded() { return expanded; }
        boolean error() { return error; }

        void append(String value) {
            if (value != null && !value.isEmpty()) {
                if (!output.isEmpty() && output.charAt(output.length() - 1) != '\n') {
                    output.append('\n');
                }
                output.append(value);
            }
        }
    }

    private final List<Block> blocks = new ArrayList<>();
    private final Map<String, ToolBlock> toolsById = new LinkedHashMap<>();
    private TextBlock streamingAssistant;
    private String streamingSegmentId = "";
    private boolean turnAssistantSeen;
    private String status = "Ready";
    private int toolSequence;

    List<Block> blocks() {
        return List.copyOf(blocks);
    }

    String status() {
        return status;
    }

    void status(String value) {
        status = value == null ? "" : value;
    }

    void addUser(String text) {
        closeAssistant();
        turnAssistantSeen = false;
        blocks.add(new TextBlock(Kind.USER, text == null ? "" : text.trim()));
        status = "Waiting for response";
    }

    void addSystem(String text) {
        closeAssistant();
        blocks.add(new TextBlock(Kind.SYSTEM, text));
    }

    void addError(String text) {
        closeAssistant();
        blocks.add(new TextBlock(Kind.ERROR, text));
        status = "Failed";
    }

    void clear() {
        blocks.clear();
        toolsById.clear();
        streamingAssistant = null;
        streamingSegmentId = "";
        turnAssistantSeen = false;
        toolSequence = 0;
        status = "Ready";
    }

    void accept(ExecutionEvent event) {
        if (event == null) {
            return;
        }
        switch (event.getType()) {
            case THINK_START -> status = "Thinking";
            case THINK_STREAM -> {
                if (isReasoning(event)) {
                    status = "Thinking";
                    break;
                }
                String content = event.getContent();
                if (streamingAssistant == null) {
                    content = stripLeadingLineBreaks(content);
                }
                if (content == null || content.isEmpty()) {
                    break;
                }
                String segmentId = value(event, "streamSegmentId");
                if (!segmentId.isBlank() && !segmentId.equals(streamingSegmentId)) {
                    closeAssistant();
                    streamingSegmentId = segmentId;
                }
                if (streamingAssistant == null) {
                    streamingAssistant = new TextBlock(Kind.ASSISTANT, "");
                    blocks.add(streamingAssistant);
                }
                streamingAssistant.append(content);
                turnAssistantSeen = true;
                status = "Responding";
            }
            case THINK_END -> {
                closeAssistant();
                if (!hasRunningTools()) {
                    status = "Working";
                }
            }
            case FINAL_RESPONSE -> {
                if (!turnAssistantSeen && event.getContent() != null && !event.getContent().isBlank()) {
                    blocks.add(new TextBlock(Kind.ASSISTANT, event.getContent()));
                    turnAssistantSeen = true;
                }
                closeAssistant();
            }
            case TOOL_START -> {
                closeAssistant();
                streamingSegmentId = "";
                ToolBlock tool = resolveTool(event, true);
                tool.status = "running";
                status = tool.name + " running";
            }
            case TOOL_OUTPUT -> resolveTool(event, true).append(event.getContent());
            case TOOL_END -> {
                ToolBlock tool = resolveTool(event, true);
                tool.status = "done";
                tool.durationMs = duration(event);
                if (tool.output.isEmpty()) {
                    tool.append(event.getContent());
                }
                status = hasRunningTools() ? "Working" : "Responding";
            }
            case TOOL_ERROR -> {
                ToolBlock tool = resolveTool(event, true);
                tool.status = "failed";
                tool.error = true;
                tool.durationMs = duration(event);
                tool.append(event.getContent());
                status = tool.name + " failed";
            }
            case ERROR -> addError(event.getContent());
            case CUSTOM -> {
                if ("tool_progress".equals(String.valueOf(event.getMetadata().get("label")))) {
                    ToolBlock tool = resolveTool(event, false);
                    if (tool != null) {
                        tool.durationMs = duration(event);
                        status = tool.name + " running";
                    }
                }
            }
        }
    }

    List<ToolBlock> tools() {
        return blocks.stream().filter(ToolBlock.class::isInstance).map(ToolBlock.class::cast).toList();
    }

    boolean toggleTool(int index) {
        for (ToolBlock tool : tools()) {
            if (tool.index == index) {
                tool.expanded = !tool.expanded;
                return true;
            }
        }
        return false;
    }

    private ToolBlock resolveTool(ExecutionEvent event, boolean create) {
        String id = value(event, "toolId");
        if (id.isBlank()) {
            id = value(event, "toolCallId");
        }
        String name = value(event, "toolName");
        if (name.isBlank()) {
            name = value(event, "tool");
        }
        if (id.isBlank()) {
            String resolvedName = name;
            ToolBlock running = toolsById.values().stream()
                    .filter(tool -> tool.name.equals(resolvedName) && "running".equals(tool.status))
                    .reduce((first, second) -> second).orElse(null);
            if (running != null) {
                return running;
            }
            id = name + "#" + (toolSequence + 1);
        }
        ToolBlock existing = toolsById.get(id);
        if (existing != null || !create) {
            return existing;
        }
        ToolBlock tool = new ToolBlock(++toolSequence, id,
                name.isBlank() ? "tool" : name, value(event, "request"));
        toolsById.put(id, tool);
        blocks.add(tool);
        return tool;
    }

    private boolean hasRunningTools() {
        return toolsById.values().stream().anyMatch(tool -> "running".equals(tool.status));
    }

    private void closeAssistant() {
        streamingAssistant = null;
    }

    private boolean isReasoning(ExecutionEvent event) {
        Object value = event.getMetadata().get("reasoning");
        return value instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(value));
    }

    private String stripLeadingLineBreaks(String value) {
        if (value == null || value.isEmpty()) return value;
        int offset = 0;
        while (offset < value.length() && (value.charAt(offset) == '\r' || value.charAt(offset) == '\n')) {
            offset++;
        }
        return value.substring(offset);
    }

    private String value(ExecutionEvent event, String key) {
        Object value = event.getMetadata().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private long duration(ExecutionEvent event) {
        Object value = event.getMetadata().get("durationMs");
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
