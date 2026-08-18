package io.jobclaw.cli.render;

import io.jobclaw.agent.ExecutionEvent;
import org.jline.terminal.Terminal;

import java.util.ArrayList;
import java.util.List;

public class TranscriptTuiRenderer {
    private static final String RESET = "\033[0m";
    private static final String DIM = "\033[2m";
    private static final String USER = "\033[48;5;238m";
    private static final String TOOL = "\033[38;5;111m";
    private static final String ERROR = "\033[38;5;203m";

    private final Terminal terminal;
    private final boolean ansi;
    private final List<Block> blocks = new ArrayList<>();
    private final String model;
    private final String cwd;
    private String status = "";
    private ToolBlock currentTool;
    private int toolCount;
    private boolean assistantStarted;
    private boolean assistantLineOpen;
    private boolean turnAssistantSeen;
    private String assistantStreamSegmentId = "";

    public TranscriptTuiRenderer(Terminal terminal, String model, String cwd) {
        this.terminal = terminal;
        this.model = model == null || model.isBlank() ? "unknown model" : model;
        this.cwd = cwd == null || cwd.isBlank() ? System.getProperty("user.dir") : cwd;
        this.ansi = System.console() != null;
    }

    public void renderUser(String task) {
        blocks.add(new TextBlock(Kind.USER, task == null ? "" : task.trim()));
        turnAssistantSeen = false;
        assistantStreamSegmentId = "";
        status = "thinking...";
        printUser(task);
    }

    public void render(ExecutionEvent event) {
        if (event == null) {
            return;
        }
        switch (event.getType()) {
            case THINK_START -> {
                status = "thinking...";
                assistantStarted = false;
                assistantStreamSegmentId = "";
            }
            case THINK_STREAM -> {
                if (isReasoning(event)) {
                    status = "thinking...";
                    break;
                }
                String segmentId = stringValue(event.getMetadata().get("streamSegmentId"));
                if (segmentId != null && !segmentId.isBlank() && !segmentId.equals(assistantStreamSegmentId)) {
                    closeAssistantLine();
                    assistantStarted = false;
                    assistantStreamSegmentId = segmentId;
                }
                appendAssistant(event.getContent());
                printAssistantDelta(event.getContent());
                turnAssistantSeen = true;
                status = "responding...";
                return;
            }
            case THINK_END -> {
                closeAssistantLine();
                status = "";
            }
            case TOOL_START -> {
                closeAssistantLine();
                assistantStarted = false;
                assistantStreamSegmentId = "";
                currentTool = new ToolBlock(++toolCount, toolName(event), stringValue(event.getMetadata().get("request")));
                currentTool.status = "running";
                blocks.add(currentTool);
                status = "tool " + currentTool.name + " running";
                printTool(currentTool);
                return;
            }
            case TOOL_OUTPUT -> {
                if (currentTool == null) {
                    currentTool = new ToolBlock(++toolCount, toolName(event), null);
                    currentTool.status = "running";
                    blocks.add(currentTool);
                }
                currentTool.append(event.getContent());
            }
            case TOOL_END -> {
                if (currentTool == null) {
                    currentTool = new ToolBlock(++toolCount, toolName(event), null);
                    blocks.add(currentTool);
                }
                currentTool.status = "done" + durationSuffix(event);
                if (currentTool.output.isEmpty()) {
                    currentTool.append(event.getContent());
                }
                currentTool = null;
                status = "";
                printTool(currentToolBlock());
                return;
            }
            case TOOL_ERROR -> {
                if (currentTool == null) {
                    currentTool = new ToolBlock(++toolCount, toolName(event), null);
                    blocks.add(currentTool);
                }
                currentTool.status = "error";
                currentTool.error = true;
                currentTool.append(event.getContent());
                ToolBlock failed = currentTool;
                currentTool = null;
                status = "";
                printTool(failed);
                return;
            }
            case FINAL_RESPONSE -> {
                if (!turnAssistantSeen && event.getContent() != null && !event.getContent().isBlank()) {
                    appendAssistant(event.getContent());
                    printAssistantDelta(event.getContent());
                    turnAssistantSeen = true;
                }
                closeAssistantLine();
                status = "";
            }
            case ERROR -> {
                closeAssistantLine();
                blocks.add(new TextBlock(Kind.ERROR, event.getContent()));
                printError(event.getContent());
                status = "";
                return;
            }
            case CUSTOM -> {
                if ("tool_progress".equals(String.valueOf(event.getMetadata().get("label")))) {
                    status = toolName(event) + " still running" + durationSuffix(event);
                }
            }
            default -> {
                // Keep the TUI focused on conversation, tool, and error events.
            }
        }
    }

    public void finish(String runId) {
        closeAssistantLine();
        status = runId == null || runId.isBlank() ? "" : "run " + runId + " complete";
        printStatus(status);
    }

    public boolean toggleTool(int index) {
        for (Block block : blocks) {
            if (block instanceof ToolBlock tool && tool.index == index) {
                tool.expanded = !tool.expanded;
                status = tool.expanded
                        ? "tool " + index + " expanded"
                        : "tool " + index + " collapsed";
                printToolDetails(tool);
                return true;
            }
        }
        return false;
    }

    private void appendAssistant(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        Block last = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
        TextBlock assistant;
        if (assistantStarted && last instanceof TextBlock text && text.kind == Kind.ASSISTANT) {
            assistant = text;
        } else {
            assistant = new TextBlock(Kind.ASSISTANT, "");
            blocks.add(assistant);
            assistantStarted = true;
        }
        assistant.text.append(content);
    }

    private void printUser(String task) {
        int width = Math.max(72, Math.min(terminal.getWidth(), 160));
        if (!ansi) {
            System.out.println("> " + (task == null ? "" : task.trim()));
            return;
        }
        terminal.writer().println();
        terminal.writer().println(color(USER, padRight("> " + (task == null ? "" : task.trim()), width)));
        terminal.flush();
    }

    private void printAssistantDelta(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        if (!assistantLineOpen) {
            terminal.writer().println();
            terminal.writer().print("• ");
            assistantLineOpen = true;
        }
        for (int offset = 0; offset < content.length(); ) {
            int cp = content.codePointAt(offset);
            if (cp == '\r') {
                offset += Character.charCount(cp);
                continue;
            }
            if (cp == '\n') {
                terminal.writer().println();
                terminal.writer().print("  ");
            } else {
                terminal.writer().print(new String(Character.toChars(cp)));
            }
            offset += Character.charCount(cp);
        }
        terminal.flush();
    }

    private void closeAssistantLine() {
        if (assistantLineOpen) {
            terminal.writer().println();
            terminal.flush();
            assistantLineOpen = false;
        }
    }

    private void printStatus(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        terminal.writer().println(dim("  " + text, ansi));
        terminal.flush();
    }

    private void printError(String text) {
        terminal.writer().println(color(ERROR, "! " + compact(text)));
        terminal.flush();
    }

    private ToolBlock currentToolBlock() {
        for (int i = blocks.size() - 1; i >= 0; i--) {
            if (blocks.get(i) instanceof ToolBlock tool) {
                return tool;
            }
        }
        return null;
    }

    private void printTool(ToolBlock tool) {
        if (tool == null) {
            return;
        }
        String marker = tool.error ? "!" : "◇";
        terminal.writer().println(color(tool.error ? ERROR : TOOL,
                marker + " tool " + tool.index + " " + tool.name + " " + tool.status));
        terminal.writer().println(dim("  collapsed; expand with /tool " + tool.index, ansi));
        terminal.flush();
    }

    private void printToolDetails(ToolBlock tool) {
        if (tool == null) {
            return;
        }
        terminal.writer().println();
        terminal.writer().println(color(tool.error ? ERROR : TOOL,
                "◇ tool " + tool.index + " " + tool.name + " " + tool.status));
        if (tool.request != null && !tool.request.isBlank()) {
            terminal.writer().println(dim("  request", ansi));
            for (String line : wrapPlain(tool.request, Math.max(20, terminal.getWidth() - 6))) {
                terminal.writer().println("    " + line);
            }
        }
        if (!tool.output.isEmpty()) {
            terminal.writer().println(dim("  result", ansi));
            for (String line : wrapPlain(tool.output.toString(), Math.max(20, terminal.getWidth() - 6))) {
                terminal.writer().println("    " + line);
            }
        }
        terminal.flush();
    }

    private void repaint() {
        if (!ansi) {
            return;
        }
        int width = Math.max(72, Math.min(terminal.getWidth(), 160));
        int height = Math.max(18, terminal.getHeight());
        int bodyHeight = Math.max(6, height - 4);
        List<String> lines = renderBlocks(width);
        int from = Math.max(0, lines.size() - bodyHeight);

        terminal.writer().print("\033[2J\033[H");
        terminal.writer().println("JobClaw Code  " + dim(model + " · " + cwd, true));
        terminal.writer().println("─".repeat(width));
        for (int i = from; i < lines.size(); i++) {
            terminal.writer().println(clip(lines.get(i), width));
        }
        for (int i = lines.size() - from; i < bodyHeight; i++) {
            terminal.writer().println();
        }
        terminal.writer().println("─".repeat(width));
        terminal.writer().println(dim(status == null ? "" : status, true));
        terminal.writer().println(dim("? for shortcuts · /tool <n> to expand collapsed tool output", true));
        terminal.flush();
    }

    private List<String> renderBlocks(int width) {
        List<String> lines = new ArrayList<>();
        int contentWidth = Math.max(20, width - 4);
        for (Block block : blocks) {
            if (block instanceof TextBlock text) {
                renderTextBlock(lines, text, contentWidth);
            } else if (block instanceof ToolBlock tool) {
                String marker = tool.error ? "!" : "◇";
                lines.add(color(tool.error ? ERROR : TOOL,
                        marker + " tool " + tool.index + " " + tool.name + " " + tool.status));
                if (tool.expanded) {
                    if (tool.request != null && !tool.request.isBlank()) {
                        lines.add(dim("  request", ansi));
                        addIndented(lines, tool.request, contentWidth);
                    }
                    if (!tool.output.isEmpty()) {
                        lines.add(dim("  result", ansi));
                        addIndented(lines, tool.output.toString(), contentWidth);
                    }
                    if ((tool.request == null || tool.request.isBlank()) && tool.output.isEmpty()) {
                        lines.add(dim("  no tool details captured", ansi));
                    }
                    lines.add(dim("  expanded; collapse with /tool " + tool.index, ansi));
                } else {
                    lines.add(dim("  collapsed; expand with /tool " + tool.index, ansi));
                }
            }
        }
        return lines;
    }

    private void addIndented(List<String> lines, String text, int width) {
        int contentWidth = Math.max(8, width - 4);
        for (String line : text.split("\\R", -1)) {
            List<String> wrapped = wrapPlain(line, contentWidth);
            for (String part : wrapped) {
                lines.add("    " + part);
            }
        }
    }

    private void renderTextBlock(List<String> lines, TextBlock block, int width) {
        String prefix = switch (block.kind) {
            case USER -> "> ";
            case ASSISTANT -> "• ";
            case ERROR -> "! ";
        };
        String color = switch (block.kind) {
            case USER -> USER;
            case ASSISTANT -> "";
            case ERROR -> ERROR;
        };
        boolean first = true;
        for (String line : block.text.toString().split("\\R", -1)) {
            if (line.isEmpty()) {
                lines.add(first ? color(color, prefix) : "  ");
                first = false;
                continue;
            }
            List<String> wrapped = wrapPlain(line, Math.max(1, width - 2));
            for (String part : wrapped) {
                lines.add(color(color, (first ? prefix : "  ") + part));
                first = false;
            }
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
        return ms < 1000 ? " " + ms + "ms" : " " + String.format("%.1fs", ms / 1000.0);
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private boolean isReasoning(ExecutionEvent event) {
        Object value = event.getMetadata().get("reasoning");
        return value instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(value));
    }

    private String compact(String content) {
        if (content == null) {
            return "";
        }
        String compact = content.replace("\r", " ").replace("\n", " ").trim();
        return compact.length() > 120 ? compact.substring(0, 120) + "..." : compact;
    }

    private String padRight(String value, int width) {
        String text = value == null ? "" : value;
        int padding = Math.max(0, width - displayWidth(text));
        return text + " ".repeat(padding);
    }

    private String clip(String value, int width) {
        String plain = value.replaceAll("\\u001B\\[[;\\d]*m", "");
        if (displayWidth(plain) <= width) {
            return value;
        }
        return clipPlain(plain, width);
    }

    private String clipPlain(String value, int width) {
        if (value == null || displayWidth(value) <= width) {
            return value == null ? "" : value;
        }
        if (width <= 3) {
            return takeCells(value, width);
        }
        return takeCells(value, width - 3) + "...";
    }

    private List<String> wrapPlain(String value, int width) {
        if (value == null || value.isEmpty()) {
            return List.of("");
        }
        List<String> result = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int cells = 0;
        for (int offset = 0; offset < value.length(); ) {
            int cp = value.codePointAt(offset);
            int charCount = Character.charCount(cp);
            int charWidth = charWidth(cp);
            if (cells > 0 && cells + charWidth > width) {
                result.add(line.toString());
                line.setLength(0);
                cells = 0;
            }
            line.appendCodePoint(cp);
            cells += charWidth;
            offset += charCount;
        }
        result.add(line.toString());
        return result;
    }

    private String takeCells(String value, int width) {
        StringBuilder builder = new StringBuilder();
        int cells = 0;
        for (int offset = 0; offset < value.length(); ) {
            int cp = value.codePointAt(offset);
            int charWidth = charWidth(cp);
            if (cells + charWidth > width) {
                break;
            }
            builder.appendCodePoint(cp);
            cells += charWidth;
            offset += Character.charCount(cp);
        }
        return builder.toString();
    }

    private int displayWidth(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int width = 0;
        for (int offset = 0; offset < value.length(); ) {
            int cp = value.codePointAt(offset);
            width += charWidth(cp);
            offset += Character.charCount(cp);
        }
        return width;
    }

    private int charWidth(int codePoint) {
        if (Character.isISOControl(codePoint)) {
            return 0;
        }
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {
            return 2;
        }
        return 1;
    }

    private String dim(String text, boolean enabled) {
        return enabled ? DIM + text + RESET : text;
    }

    private String color(String code, String text) {
        return ansi && code != null && !code.isBlank() ? code + text + RESET : text;
    }

    private enum Kind {
        USER,
        ASSISTANT,
        ERROR
    }

    private interface Block {
    }

    private static final class TextBlock implements Block {
        private final Kind kind;
        private final StringBuilder text = new StringBuilder();

        private TextBlock(Kind kind, String text) {
            this.kind = kind;
            if (text != null) {
                this.text.append(text);
            }
        }
    }

    private static final class ToolBlock implements Block {
        private final int index;
        private final String name;
        private final String request;
        private final StringBuilder output = new StringBuilder();
        private String status = "running";
        private boolean error;
        private boolean expanded;

        private ToolBlock(int index, String name, String request) {
            this.index = index;
            this.name = name == null || name.isBlank() ? "tool" : name;
            this.request = request;
        }

        private void append(String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            if (!output.isEmpty()) {
                output.append('\n');
            }
            output.append(value.strip());
        }
    }
}
