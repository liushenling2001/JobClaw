package io.jobclaw.agent.checkpoint;

import io.jobclaw.agent.TaskHarnessRun;
import io.jobclaw.agent.TaskHarnessSubtask;
import io.jobclaw.agent.TaskHarnessSubtaskStatus;
import io.jobclaw.agent.planning.TaskPlanningMode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class TaskCheckpointService {

    private final TaskCheckpointStore store;

    public TaskCheckpointService(TaskCheckpointStore store) {
        this.store = store;
    }

    public void save(TaskHarnessRun run) {
        if (run == null) {
            return;
        }
        String planSnapshot = run.planExecutionSnapshot();
        boolean hasPlanSnapshot = planSnapshot != null && !planSnapshot.isBlank();
        if (run.getPlanningMode() == TaskPlanningMode.DIRECT && !run.hasTrackedSubtasks() && !hasPlanSnapshot) {
            return;
        }
        if (run.getPlanningMode() == TaskPlanningMode.WORKLIST && !run.hasTrackedSubtasks() && !hasPlanSnapshot) {
            return;
        }
        List<TaskHarnessSubtask> subtasks = run.getSubtasks();
        long completedCount = subtasks.stream()
                .filter(subtask -> subtask.status() == TaskHarnessSubtaskStatus.COMPLETED)
                .count();
        Optional<TaskHarnessSubtask> nextPending = subtasks.stream()
                .filter(subtask -> subtask.status() == TaskHarnessSubtaskStatus.PLANNED
                        || subtask.status() == TaskHarnessSubtaskStatus.RUNNING)
                .findFirst();

        String lastStableSummary = hasPlanSnapshot
                ? firstLine(planSnapshot)
                : completedCount > 0
                ? "已完成 " + completedCount + " 个子任务"
                : "尚未完成任何子任务";
        String nextAction = nextPending
                .map(subtask -> "继续处理子任务: " + titleOrId(subtask))
                .orElse("全部子任务已完成，输出最终汇总");

        store.save(new TaskCheckpoint(
                run.getSessionId(),
                run.getRunId(),
                run.getTaskInput(),
                run.getPlanningMode(),
                run.getPendingSubtaskCount(),
                lastStableSummary,
                nextAction,
                subtasks.stream()
                        .map(subtask -> new TaskCheckpoint.SubtaskSnapshot(
                                subtask.id(),
                                subtask.title(),
                                subtask.status().name(),
                                subtask.summary()
                        ))
                        .toList(),
                hasPlanSnapshot ? planSnapshot : "",
                Instant.now()
        ));
    }

    public Optional<TaskCheckpoint> latestResumable(String sessionId, String taskInput, TaskPlanningMode planningMode) {
        if (planningMode == TaskPlanningMode.DIRECT && !isResumeRequest(taskInput)) {
            return Optional.empty();
        }
        return store.latest(sessionId)
                .filter(checkpoint -> isResumeRequest(taskInput)
                        || (checkpoint.taskInput() != null
                        && normalize(checkpoint.taskInput()).equals(normalize(taskInput))))
                .filter(this::isResumable);
    }

    public Optional<TaskCheckpoint> latestForResumeRequest(String sessionId, String taskInput) {
        if (!isResumeRequest(taskInput)) {
            return Optional.empty();
        }
        return store.latest(sessionId).filter(this::isResumable);
    }

    public String buildResumeGuidance(TaskCheckpoint checkpoint) {
        if (checkpoint == null) {
            return "";
        }
        List<TaskCheckpoint.SubtaskSnapshot> pending = checkpoint.subtasks().stream()
                .filter(snapshot -> "PLANNED".equals(snapshot.status()) || "RUNNING".equals(snapshot.status()))
                .sorted(Comparator.comparing(TaskCheckpoint.SubtaskSnapshot::id))
                .toList();
        List<TaskCheckpoint.SubtaskSnapshot> completed = checkpoint.subtasks().stream()
                .filter(snapshot -> "COMPLETED".equals(snapshot.status()))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("[Resume From Checkpoint]\n");
        sb.append(checkpoint.lastStableSummary()).append("\n");
        if (checkpoint.planExecutionSnapshot() != null && !checkpoint.planExecutionSnapshot().isBlank()) {
            sb.append(checkpoint.planExecutionSnapshot()).append("\n");
        }
        if (!completed.isEmpty()) {
            sb.append("已完成子任务:\n");
            for (TaskCheckpoint.SubtaskSnapshot snapshot : completed.stream().limit(20).toList()) {
                sb.append("- ").append(titleOrId(snapshot)).append(" (").append(snapshot.id()).append(")\n");
            }
        }
        if (!pending.isEmpty()) {
            sb.append("待继续子任务:\n");
            for (TaskCheckpoint.SubtaskSnapshot snapshot : pending.stream().limit(20).toList()) {
                sb.append("- ").append(titleOrId(snapshot)).append(" (").append(snapshot.id()).append(")\n");
            }
        }
        sb.append("不要重复已完成子任务。");
        return sb.toString();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replace("\r", "").toLowerCase(Locale.ROOT);
    }

    private boolean isResumeRequest(String value) {
        String normalized = normalize(value);
        return normalized.equals("继续")
                || normalized.equals("继续执行")
                || normalized.equals("继续任务")
                || normalized.equals("恢复")
                || normalized.equals("恢复执行")
                || normalized.equals("resume")
                || normalized.equals("continue")
                || normalized.equals("retry")
                || normalized.equals("重试");
    }

    private boolean isResumable(TaskCheckpoint checkpoint) {
        return checkpoint != null
                && (checkpoint.pendingSubtasks() > 0
                || (checkpoint.planExecutionSnapshot() != null && !checkpoint.planExecutionSnapshot().isBlank()));
    }

    private String firstLine(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replace("\r", "").trim();
        int newline = normalized.indexOf('\n');
        return newline >= 0 ? normalized.substring(0, newline) : normalized;
    }

    private String titleOrId(TaskHarnessSubtask subtask) {
        return subtask.title() != null && !subtask.title().isBlank() ? subtask.title() : subtask.id();
    }

    private String titleOrId(TaskCheckpoint.SubtaskSnapshot snapshot) {
        return snapshot.title() != null && !snapshot.title().isBlank() ? snapshot.title() : snapshot.id();
    }
}
