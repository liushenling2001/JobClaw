package io.jobclaw.tools;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.ExecutionEvent;
import io.jobclaw.agent.TaskHarnessService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class SubtasksTool {
    private static final int STATUS_DETAIL_LIMIT = 30;

    private final TaskHarnessService taskHarnessService;

    public SubtasksTool(TaskHarnessService taskHarnessService) {
        this.taskHarnessService = taskHarnessService;
    }

    @Tool(name = "subtasks", description = "Track a worklist of independent subtasks within the current task harness run. Use plan before batch work, then start/complete/fail per subtask. The parent task will not be allowed to finish while planned or running subtasks remain.")
    public String subtasks(
            @ToolParam(description = "Action: plan, start, complete, fail, status") String action,
            @ToolParam(description = "Subtask id for start/complete/fail. Use a stable identifier such as a file path.") String id,
            @ToolParam(description = "Subtask title for plan/start. Optional.") String title,
            @ToolParam(description = "Planned items for action=plan. One item per line. Format: id|title or just id.") String items,
            @ToolParam(description = "Summary or result for complete/fail. Optional.") String summary
    ) {
        AgentExecutionContext.ExecutionScope scope = AgentExecutionContext.getCurrentScope();
        if (scope == null || scope.runId() == null || scope.runId().isBlank()) {
            return "Error: subtasks tool requires an active task harness run";
        }

        String normalizedAction = action == null ? "" : action.trim().toLowerCase();
        Consumer<ExecutionEvent> eventCallback = scope.eventCallback();
        return switch (normalizedAction) {
            case "plan" -> plan(scope.runId(), items, eventCallback);
            case "start" -> start(scope.runId(), id, title, eventCallback);
            case "complete" -> complete(scope.runId(), id, summary, true, eventCallback);
            case "fail" -> complete(scope.runId(), id, summary, false, eventCallback);
            case "status" -> status(scope.runId());
            default -> "Error: unsupported action. Use plan, start, complete, fail, or status";
        };
    }

    private String plan(String runId, String items, Consumer<ExecutionEvent> eventCallback) {
        List<SubtaskSpec> specs = parseItems(items);
        if (specs.isEmpty()) {
            return "Error: items is required for plan";
        }
        int planned = 0;
        for (SubtaskSpec spec : specs) {
            taskHarnessService.planSubtask(runId, spec.id(), spec.title(), Map.of("source", "subtasks_tool"), eventCallback);
            planned++;
        }
        return "Planned " + planned + " subtasks";
    }

    private String start(String runId, String id, String title, Consumer<ExecutionEvent> eventCallback) {
        if (id == null || id.isBlank()) {
            return "Error: id is required for start";
        }
        taskHarnessService.startSubtask(runId, id.trim(), title, null, Map.of("source", "subtasks_tool"), eventCallback);
        return "Started subtask: " + id.trim();
    }

    private String complete(String runId,
                            String id,
                            String summary,
                            boolean success,
                            Consumer<ExecutionEvent> eventCallback) {
        if (id == null || id.isBlank()) {
            return "Error: id is required";
        }
        taskHarnessService.completeSubtask(
                runId,
                id.trim(),
                summary,
                success,
                Map.of("source", "subtasks_tool"),
                eventCallback
        );
        return (success ? "Completed subtask: " : "Failed subtask: ") + id.trim();
    }

    private String status(String runId) {
        var run = taskHarnessService.getRun(runId);
        if (run == null) {
            return "Error: task harness run not found";
        }
        List<String> lines = new ArrayList<>();
        var subtasks = run.getSubtasks();
        lines.add("Total subtasks: " + subtasks.size());
        lines.add("Pending subtasks: " + run.getPendingSubtaskCount());
        int emitted = 0;
        for (var subtask : subtasks) {
            if (emitted >= STATUS_DETAIL_LIMIT) {
                break;
            }
            lines.add("- [" + subtask.status().name().toLowerCase() + "] "
                    + (subtask.title() != null && !subtask.title().isBlank() ? subtask.title() : subtask.id())
                    + " (" + subtask.id() + ")");
            emitted++;
        }
        int remaining = subtasks.size() - emitted;
        if (remaining > 0) {
            lines.add("... " + remaining + " more subtasks omitted from status output");
        }
        return String.join("\n", lines);
    }

    private List<SubtaskSpec> parseItems(String items) {
        if (items == null || items.isBlank()) {
            return List.of();
        }
        List<SubtaskSpec> specs = new ArrayList<>();
        String[] lines = items.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            int separatorIndex = line.indexOf('|');
            if (separatorIndex >= 0) {
                String id = line.substring(0, separatorIndex).trim();
                String title = line.substring(separatorIndex + 1).trim();
                if (!id.isEmpty()) {
                    specs.add(new SubtaskSpec(id, title.isEmpty() ? id : title));
                }
            } else {
                specs.add(new SubtaskSpec(line, line));
            }
        }
        return specs;
    }

    private record SubtaskSpec(String id, String title) {
    }
}
