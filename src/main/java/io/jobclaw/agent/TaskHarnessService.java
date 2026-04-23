package io.jobclaw.agent;

import io.jobclaw.agent.checkpoint.TaskCheckpointService;
import io.jobclaw.agent.completion.ActiveExecutionRegistry;
import io.jobclaw.agent.artifact.RunArtifact;
import io.jobclaw.agent.artifact.RunArtifactStore;
import io.jobclaw.agent.planning.PlanExecutionState;
import io.jobclaw.agent.planning.PlanReviewAction;
import io.jobclaw.agent.planning.PlanReviewDecision;
import io.jobclaw.agent.planning.PlanStep;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
public class TaskHarnessService {

    private final ConcurrentHashMap<String, TaskHarnessRun> runs = new ConcurrentHashMap<>();
    private final TaskCheckpointService taskCheckpointService;
    private final ActiveExecutionRegistry activeExecutionRegistry;
    private final RunArtifactStore runArtifactStore;

    public TaskHarnessService() {
        this(new TaskCheckpointService(new io.jobclaw.agent.checkpoint.TaskCheckpointStore() {
            @Override
            public void save(io.jobclaw.agent.checkpoint.TaskCheckpoint checkpoint) {
            }

            @Override
            public java.util.Optional<io.jobclaw.agent.checkpoint.TaskCheckpoint> latest(String sessionId) {
                return java.util.Optional.empty();
            }
        }), new ActiveExecutionRegistry(), new RunArtifactStore() {
            @Override
            public RunArtifact save(String runId, String stepId, String name, String content, String summary) {
                return new RunArtifact(runId, stepId, name, "", summary, content != null ? content.length() : 0, java.time.Instant.now());
            }

            @Override
            public java.util.List<RunArtifact> list(String runId) {
                return java.util.List.of();
            }

            @Override
            public String buildIndex(String runId) {
                return "";
            }
        });
    }

    @Autowired
    public TaskHarnessService(TaskCheckpointService taskCheckpointService,
                              ActiveExecutionRegistry activeExecutionRegistry,
                              RunArtifactStore runArtifactStore) {
        this.taskCheckpointService = taskCheckpointService;
        this.activeExecutionRegistry = activeExecutionRegistry;
        this.runArtifactStore = runArtifactStore;
    }

    public TaskHarnessRun startRun(String sessionId,
                                   String runId,
                                   String taskInput,
                                   Consumer<ExecutionEvent> eventCallback) {
        TaskHarnessRun run = new TaskHarnessRun(sessionId, runId, taskInput);
        runs.put(runId, run);
        transition(run, TaskHarnessPhase.PLAN, "started", "task_harness", "Task harness started", Map.of(), eventCallback);
        return run;
    }

    public TaskHarnessStep transition(TaskHarnessRun run,
                                      TaskHarnessPhase phase,
                                      String status,
                                      String label,
                                      String detail,
                                      Map<String, Object> metadata,
                                      Consumer<ExecutionEvent> eventCallback) {
        TaskHarnessStep step = run.addStep(phase, status, label, detail, metadata);
        publish(run, step, eventCallback);
        return step;
    }

    public void initializePlan(TaskHarnessRun run,
                               List<PlanStep> steps,
                               Consumer<ExecutionEvent> eventCallback) {
        if (run == null || steps == null || steps.isEmpty()) {
            return;
        }
        run.initializePlanExecution(steps);
        transition(
                run,
                TaskHarnessPhase.PLAN,
                "planned",
                "explicit_plan",
                "Explicit task plan initialized",
                Map.of(
                        "stepCount", steps.size(),
                        "planSnapshot", run.planExecutionSnapshot()
                ),
                eventCallback
        );
        taskCheckpointService.save(run);
    }

    public Consumer<ExecutionEvent> wrapEventCallback(TaskHarnessRun run, Consumer<ExecutionEvent> delegate) {
        return event -> {
            recordFromEvent(run, event);
            if (delegate != null) {
                delegate.accept(event);
            }
        };
    }

    public TaskHarnessFailure recordFailure(TaskHarnessRun run,
                                            TaskHarnessFailure failure,
                                            Consumer<ExecutionEvent> eventCallback) {
        run.recordFailure(failure);
        transition(
                run,
                TaskHarnessPhase.REPAIR,
                "classified",
                "failure_classified",
                failure.reason(),
                Map.of(
                        "kind", failure.kind().name(),
                        "evidence", truncate(failure.evidence(), 500)
                ),
                eventCallback
        );
        taskCheckpointService.save(run);
        return failure;
    }

    public void complete(TaskHarnessRun run, boolean success, String detail, Consumer<ExecutionEvent> eventCallback) {
        if (success) {
            run.clearFailure();
        }
        transition(
                run,
                success ? TaskHarnessPhase.FINISH : TaskHarnessPhase.FAILED,
                success ? "success" : "failed",
                success ? "completed" : "failed",
                detail,
                Map.of("repairAttempts", run.getRepairAttempts()),
                eventCallback
        );
        run.complete(success);
        if (!success) {
            taskCheckpointService.save(run);
        }
    }

    public void recordCompletionDecision(TaskHarnessRun run,
                                         String status,
                                         String reason,
                                         java.util.List<String> missingRequirements,
                                         Consumer<ExecutionEvent> eventCallback) {
        HashMap<String, Object> metadata = new HashMap<>();
        metadata.put("decisionStatus", status);
        if (missingRequirements != null && !missingRequirements.isEmpty()) {
            metadata.put("missingRequirements", String.join(",", missingRequirements));
        }
        transition(
                run,
                TaskHarnessPhase.REVIEW,
                "decision",
                "completion_decision",
                reason,
                metadata,
                eventCallback
        );
    }

    public void recordPlanReviewDecision(TaskHarnessRun run,
                                         PlanReviewDecision decision,
                                         Consumer<ExecutionEvent> eventCallback) {
        if (run == null || decision == null) {
            return;
        }
        int attempt = run.incrementPlanReviewAttempts();
        if (decision.action() == PlanReviewAction.SPLIT_STEP
                && run.getPlanExecutionState() != null
                && run.getPlanExecutionState().splitCurrentStep()) {
            taskCheckpointService.save(run);
        }
        HashMap<String, Object> metadata = new HashMap<>();
        metadata.put("action", decision.action().name());
        metadata.put("attempt", attempt);
        if (!decision.instructions().isEmpty()) {
            metadata.put("instructions", String.join(" | ", decision.instructions()));
        }
        transition(
                run,
                TaskHarnessPhase.PLAN,
                "reviewed",
                "plan_review",
                decision.reason(),
                metadata,
                eventCallback
        );
    }

    public String buildPlanExecutionSnapshot(TaskHarnessRun run) {
        if (run == null) {
            return "";
        }
        String snapshot = run.planExecutionSnapshot();
        return snapshot != null ? snapshot : "";
    }

    public String buildStepRuntimeContext(TaskHarnessRun run) {
        if (run == null || run.getPlanExecutionState() == null || run.getPlanExecutionState().isEmpty()) {
            return "";
        }
        run.getPlanExecutionState().startCurrentStep();
        String artifactIndex = runArtifactStore.buildIndex(run.getRunId());
        StringBuilder sb = new StringBuilder();
        sb.append(run.getPlanExecutionState().currentStepContract()).append("\n");
        String snapshot = run.planExecutionSnapshot();
        if (snapshot != null && !snapshot.isBlank()) {
            sb.append(snapshot).append("\n\n");
        }
        if (artifactIndex != null && !artifactIndex.isBlank()) {
            sb.append(artifactIndex).append("\n\n");
        }
        sb.append("""
                [Step Scoped Execution Policy]
                Use only the context needed for the current step.
                If this step needs to inspect multiple large files or isolate expensive reasoning, use `spawn` for the step or per-source analysis.
                Any large intermediate result must be summarized and stored as a process artifact; do not paste full source content into the parent response.
                Return a short handoff summary with paths/refIds of process artifacts.
                """);
        return sb.toString();
    }

    public void completeCurrentPlanStep(TaskHarnessRun run,
                                        String summary,
                                        Consumer<ExecutionEvent> eventCallback) {
        if (run == null || run.getPlanExecutionState() == null || run.getPlanExecutionState().isEmpty()) {
            return;
        }
        String stepId = run.getPlanExecutionState().currentStepId();
        RunArtifact artifact = runArtifactStore.save(
                run.getRunId(),
                stepId,
                "handoff",
                buildStepArtifactContent(run, summary),
                concise(summary, 180)
        );
        run.getPlanExecutionState().completeCurrentStep(
                summary,
                Map.of("artifactPath", artifact.path()),
                artifact.path()
        );
        transition(
                run,
                TaskHarnessPhase.OBSERVE,
                "step_completed",
                "plan_step_completed",
                "Plan step completed: " + stepId,
                Map.of("stepId", stepId, "artifactPath", artifact.path()),
                eventCallback
        );
        taskCheckpointService.save(run);
    }

    public TaskHarnessRun getRun(String runId) {
        return runs.get(runId);
    }

    public TaskHarnessSubtask planSubtask(String runId,
                                          String subtaskId,
                                          String title,
                                          Map<String, Object> metadata,
                                          Consumer<ExecutionEvent> eventCallback) {
        TaskHarnessRun run = runs.get(runId);
        if (run == null || subtaskId == null || subtaskId.isBlank()) {
            return null;
        }
        TaskHarnessSubtask subtask = run.upsertPlannedSubtask(subtaskId, title, metadata);
        publishSubtask(run, subtask, "planned", "subtask_planned",
                "Subtask planned: " + titleOrId(subtask), eventCallback);
        return subtask;
    }

    public TaskHarnessSubtask startSubtask(String runId,
                                           String subtaskId,
                                           String title,
                                           String childSessionId,
                                           Map<String, Object> metadata,
                                           Consumer<ExecutionEvent> eventCallback) {
        TaskHarnessRun run = runs.get(runId);
        if (run == null || subtaskId == null || subtaskId.isBlank()) {
            return null;
        }
        TaskHarnessSubtask subtask = run.markSubtaskRunning(subtaskId, title, childSessionId, metadata);
        activeExecutionRegistry.subagentStarted(run.getSessionId());
        publishSubtask(run, subtask, "running", "subtask_started",
                "Subtask started: " + titleOrId(subtask), eventCallback);
        return subtask;
    }

    public TaskHarnessSubtask completeSubtask(String runId,
                                              String subtaskId,
                                              String summary,
                                              boolean success,
                                              Map<String, Object> metadata,
                                              Consumer<ExecutionEvent> eventCallback) {
        TaskHarnessRun run = runs.get(runId);
        if (run == null || subtaskId == null || subtaskId.isBlank()) {
            return null;
        }
        TaskHarnessSubtask subtask = run.markSubtaskCompleted(subtaskId, summary, success, metadata);
        activeExecutionRegistry.subagentFinished(run.getSessionId());
        publishSubtask(run, subtask, success ? "success" : "failed",
                success ? "subtask_completed" : "subtask_failed",
                summary != null && !summary.isBlank() ? summary : "Subtask finished: " + titleOrId(subtask),
                eventCallback);
        taskCheckpointService.save(run);
        return subtask;
    }

    private void recordFromEvent(TaskHarnessRun run, ExecutionEvent event) {
        Map<String, Object> metadata = new java.util.HashMap<>(event.getMetadata());
        TaskHarnessPhase phase = "tool_progress".equals(stringValue(metadata.get("label")))
                ? TaskHarnessPhase.ACT
                : mapPhase(event.getType());
        metadata.put("eventType", event.getType().name());
        run.addStep(phase, "event", event.getType().name().toLowerCase(), event.getContent(), metadata);
        recordPlanEvidence(run, event, metadata);
    }

    private void recordPlanEvidence(TaskHarnessRun run, ExecutionEvent event, Map<String, Object> metadata) {
        PlanExecutionState state = run.getPlanExecutionState();
        if (state == null || state.isEmpty()) {
            return;
        }
        String eventType = event.getType().name();
        String label = stringValue(metadata.get("label"));
        if (event.getType() == ExecutionEvent.EventType.TOOL_START
                || event.getType() == ExecutionEvent.EventType.TOOL_END
                || event.getType() == ExecutionEvent.EventType.TOOL_OUTPUT
                || event.getType() == ExecutionEvent.EventType.TOOL_ERROR) {
            if (event.getType() == ExecutionEvent.EventType.TOOL_OUTPUT && event.getContent() != null && event.getContent().length() > 3000) {
                String stepId = state.currentStepId();
                RunArtifact artifact = runArtifactStore.save(
                        run.getRunId(),
                        stepId,
                        safeArtifactName(stringValue(metadata.get("toolName"))),
                        event.getContent(),
                        "Large tool output captured for step " + stepId
                );
                metadata.put("artifactPath", artifact.path());
            }
            state.recordToolEvent(
                    eventType,
                    stringValue(metadata.get("toolName")),
                    event.getContent(),
                    metadata
            );
            taskCheckpointService.save(run);
            return;
        }
        if (label.startsWith("subtask_")) {
            state.recordSubtaskEvidence(stringValue(metadata.get("status")), event.getContent(), metadata);
            taskCheckpointService.save(run);
        }
    }

    private void publish(TaskHarnessRun run, TaskHarnessStep step, Consumer<ExecutionEvent> eventCallback) {
        if (eventCallback == null) {
            return;
        }
        eventCallback.accept(new ExecutionEvent(
                run.getSessionId(),
                ExecutionEvent.EventType.CUSTOM,
                step.detail(),
                buildHarnessEventMetadata(step),
                run.getRunId(),
                null,
                "task_harness",
                "Task Harness"
        ));
    }

    private TaskHarnessPhase mapPhase(ExecutionEvent.EventType eventType) {
        return switch (eventType) {
            case THINK_START -> TaskHarnessPhase.PLAN;
            case THINK_STREAM, THINK_END -> TaskHarnessPhase.OBSERVE;
            case TOOL_START -> TaskHarnessPhase.ACT;
            case TOOL_END, TOOL_OUTPUT -> TaskHarnessPhase.OBSERVE;
            case TOOL_ERROR -> TaskHarnessPhase.REPAIR;
            case FINAL_RESPONSE -> TaskHarnessPhase.REVIEW;
            case ERROR -> TaskHarnessPhase.REPAIR;
            case CUSTOM -> TaskHarnessPhase.OBSERVE;
            default -> TaskHarnessPhase.OBSERVE;
        };
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n[truncated]";
    }

    private String concise(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replace("\r", "").replace("\n", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private Map<String, Object> buildHarnessEventMetadata(TaskHarnessStep step) {
        java.util.HashMap<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("source", "task_harness");
        metadata.put("phase", step.phase().name());
        metadata.put("status", step.status());
        metadata.put("label", step.label());
        metadata.put("stepIndex", step.index());
        Object failureType = step.metadata().get("failureType");
        if (failureType != null) {
            metadata.put("failureType", failureType);
        }
        Object kind = step.metadata().get("kind");
        if (kind != null) {
            metadata.put("kind", kind);
        }
        return metadata;
    }

    private void publishSubtask(TaskHarnessRun run,
                                TaskHarnessSubtask subtask,
                                String status,
                                String label,
                                String detail,
                                Consumer<ExecutionEvent> eventCallback) {
        HashMap<String, Object> metadata = new HashMap<>();
        metadata.put("subtaskId", subtask.id());
        metadata.put("subtaskTitle", titleOrId(subtask));
        metadata.put("subtaskStatus", subtask.status().name());
        metadata.put("pendingSubtasks", run.getPendingSubtaskCount());
        if (subtask.childSessionId() != null) {
            metadata.put("childSessionId", subtask.childSessionId());
        }
        transition(run, TaskHarnessPhase.OBSERVE, status, label, detail, metadata, eventCallback);
    }

    private String titleOrId(TaskHarnessSubtask subtask) {
        return subtask.title() != null && !subtask.title().isBlank() ? subtask.title() : subtask.id();
    }

    private String buildStepArtifactContent(TaskHarnessRun run, String summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Step Handoff\n\n");
        sb.append("runId: ").append(run.getRunId()).append("\n");
        sb.append("stepId: ").append(run.getPlanExecutionState().currentStepId()).append("\n\n");
        sb.append("## Summary\n\n");
        sb.append(summary != null ? summary : "").append("\n\n");
        sb.append("## Plan State\n\n");
        sb.append(run.planExecutionSnapshot()).append("\n");
        return sb.toString();
    }

    private String safeArtifactName(String value) {
        String text = value == null || value.isBlank() ? "tool-output" : value;
        return text.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
