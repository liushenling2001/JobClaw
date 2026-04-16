package io.jobclaw.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TaskHarnessRun {

    private final String sessionId;
    private final String runId;
    private final String taskInput;
    private final Instant startedAt;
    private final List<TaskHarnessStep> steps;
    private TaskHarnessPhase currentPhase;
    private Instant completedAt;
    private boolean success;
    private int repairAttempts;
    private TaskHarnessFailure lastFailure;
    private TaskHarnessVerificationResult lastVerificationResult;
    private final Map<String, TaskHarnessSubtask> subtasks;

    public TaskHarnessRun(String sessionId, String runId, String taskInput) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.taskInput = taskInput;
        this.startedAt = Instant.now();
        this.steps = new ArrayList<>();
        this.subtasks = new LinkedHashMap<>();
        this.currentPhase = TaskHarnessPhase.PLAN;
    }

    public synchronized TaskHarnessStep addStep(TaskHarnessPhase phase,
                                                String status,
                                                String label,
                                                String detail,
                                                java.util.Map<String, Object> metadata) {
        this.currentPhase = phase;
        TaskHarnessStep step = new TaskHarnessStep(steps.size() + 1, phase, status, label, detail, Instant.now(), metadata);
        this.steps.add(step);
        return step;
    }

    public synchronized void complete(boolean success) {
        this.success = success;
        this.completedAt = Instant.now();
        this.currentPhase = success ? TaskHarnessPhase.FINISH : TaskHarnessPhase.FAILED;
    }

    public synchronized int incrementRepairAttempts() {
        repairAttempts++;
        return repairAttempts;
    }

    public synchronized void recordVerificationResult(TaskHarnessVerificationResult verificationResult) {
        this.lastVerificationResult = verificationResult;
    }

    public synchronized void recordFailure(TaskHarnessFailure failure) {
        this.lastFailure = failure;
    }

    public synchronized void clearFailure() {
        this.lastFailure = null;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRunId() {
        return runId;
    }

    public String getTaskInput() {
        return taskInput;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public synchronized List<TaskHarnessStep> getSteps() {
        return Collections.unmodifiableList(new ArrayList<>(steps));
    }

    public synchronized TaskHarnessPhase getCurrentPhase() {
        return currentPhase;
    }

    public synchronized Instant getCompletedAt() {
        return completedAt;
    }

    public synchronized boolean isSuccess() {
        return success;
    }

    public synchronized int getRepairAttempts() {
        return repairAttempts;
    }

    public synchronized TaskHarnessFailure getLastFailure() {
        return lastFailure;
    }

    public synchronized TaskHarnessVerificationResult getLastVerificationResult() {
        return lastVerificationResult;
    }

    public synchronized TaskHarnessSubtask upsertPlannedSubtask(String id,
                                                                String title,
                                                                Map<String, Object> metadata) {
        TaskHarnessSubtask existing = subtasks.get(id);
        TaskHarnessSubtask planned = existing == null
                ? new TaskHarnessSubtask(id, title, TaskHarnessSubtaskStatus.PLANNED, null, null, null, null, metadata)
                : existing.withStatus(TaskHarnessSubtaskStatus.PLANNED, null, null, null, null, mergeMetadata(existing.metadata(), metadata));
        subtasks.put(id, planned);
        return planned;
    }

    public synchronized TaskHarnessSubtask markSubtaskRunning(String id,
                                                              String title,
                                                              String childSessionId,
                                                              Map<String, Object> metadata) {
        TaskHarnessSubtask existing = subtasks.get(id);
        TaskHarnessSubtask running = (existing == null
                ? new TaskHarnessSubtask(id, title, TaskHarnessSubtaskStatus.RUNNING, null, childSessionId, Instant.now(), null, metadata)
                : existing.withStatus(TaskHarnessSubtaskStatus.RUNNING, null, childSessionId, Instant.now(), null,
                mergeMetadata(existing.metadata(), metadata)));
        subtasks.put(id, running);
        return running;
    }

    public synchronized TaskHarnessSubtask markSubtaskCompleted(String id,
                                                                String summary,
                                                                boolean success,
                                                                Map<String, Object> metadata) {
        TaskHarnessSubtask existing = subtasks.get(id);
        String title = existing != null ? existing.title() : id;
        String childSessionId = existing != null ? existing.childSessionId() : null;
        Instant startedAt = existing != null ? existing.startedAt() : Instant.now();
        TaskHarnessSubtask completed = new TaskHarnessSubtask(
                id,
                title,
                success ? TaskHarnessSubtaskStatus.COMPLETED : TaskHarnessSubtaskStatus.FAILED,
                summary,
                childSessionId,
                startedAt,
                Instant.now(),
                mergeMetadata(existing != null ? existing.metadata() : null, metadata)
        );
        subtasks.put(id, completed);
        return completed;
    }

    public synchronized int getPendingSubtaskCount() {
        return (int) subtasks.values().stream()
                .filter(subtask -> subtask.status() == TaskHarnessSubtaskStatus.PLANNED
                        || subtask.status() == TaskHarnessSubtaskStatus.RUNNING)
                .count();
    }

    public synchronized boolean hasTrackedSubtasks() {
        return !subtasks.isEmpty();
    }

    public synchronized List<TaskHarnessSubtask> getSubtasks() {
        return List.copyOf(subtasks.values());
    }

    private Map<String, Object> mergeMetadata(Map<String, Object> base, Map<String, Object> updates) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (base != null) {
            merged.putAll(base);
        }
        if (updates != null) {
            merged.putAll(updates);
        }
        return merged;
    }
}
