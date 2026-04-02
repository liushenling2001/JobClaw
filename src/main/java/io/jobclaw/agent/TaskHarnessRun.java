package io.jobclaw.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    public TaskHarnessRun(String sessionId, String runId, String taskInput) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.taskInput = taskInput;
        this.startedAt = Instant.now();
        this.steps = new ArrayList<>();
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
}
