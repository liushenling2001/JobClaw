package io.jobclaw.agent;

import io.jobclaw.agent.checkpoint.TaskCheckpointService;
import io.jobclaw.agent.completion.ActiveExecutionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
public class TaskHarnessService {

    private final ConcurrentHashMap<String, TaskHarnessRun> runs = new ConcurrentHashMap<>();
    private final TaskCheckpointService taskCheckpointService;
    private final ActiveExecutionRegistry activeExecutionRegistry;

    public TaskHarnessService() {
        this(new TaskCheckpointService(new io.jobclaw.agent.checkpoint.TaskCheckpointStore() {
            @Override
            public void save(io.jobclaw.agent.checkpoint.TaskCheckpoint checkpoint) {
            }

            @Override
            public java.util.Optional<io.jobclaw.agent.checkpoint.TaskCheckpoint> latest(String sessionId) {
                return java.util.Optional.empty();
            }
        }), new ActiveExecutionRegistry());
    }

    @Autowired
    public TaskHarnessService(TaskCheckpointService taskCheckpointService,
                              ActiveExecutionRegistry activeExecutionRegistry) {
        this.taskCheckpointService = taskCheckpointService;
        this.activeExecutionRegistry = activeExecutionRegistry;
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

    public Consumer<ExecutionEvent> wrapEventCallback(TaskHarnessRun run, Consumer<ExecutionEvent> delegate) {
        return event -> {
            recordFromEvent(run, event);
            if (delegate != null) {
                delegate.accept(event);
            }
        };
    }

    public TaskHarnessVerificationResult verify(TaskHarnessRun run,
                                                String finalResponse,
                                                Throwable failure,
                                                TaskHarnessVerifier verifier,
                                                Consumer<ExecutionEvent> eventCallback) {
        transition(run, TaskHarnessPhase.VERIFY, "running", "verify", "Verifying task outcome", Map.of(), eventCallback);
        TaskHarnessVerificationResult result = verifier.verify(run, finalResponse, failure);
        run.recordVerificationResult(result);
        Map<String, Object> verifyMetadata = new java.util.HashMap<>();
        verifyMetadata.put("verified", result.success());
        if (result.reason() != null && !result.reason().isBlank()) {
            verifyMetadata.put("reason", result.reason());
        }
        if (result.failureType() != null && !result.failureType().isBlank()) {
            verifyMetadata.put("failureType", result.failureType());
        }
        if (finalResponse != null && !finalResponse.isBlank()) {
            verifyMetadata.put("finalResponse", truncate(finalResponse, 500));
        }
        if (failure != null) {
            verifyMetadata.put("exceptionType", failure.getClass().getName());
        }
        transition(run,
                result.success() ? TaskHarnessPhase.FINISH : TaskHarnessPhase.REPAIR,
                result.success() ? "success" : "failed",
                result.success() ? "verify_passed" : "verify_failed",
                result.reason(),
                verifyMetadata,
                eventCallback);
        return result;
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
                TaskHarnessPhase.VERIFY,
                "decision",
                "completion_decision",
                reason,
                metadata,
                eventCallback
        );
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
        TaskHarnessPhase phase = mapPhase(event.getType());
        Map<String, Object> metadata = new java.util.HashMap<>(event.getMetadata());
        metadata.put("eventType", event.getType().name());
        run.addStep(phase, "event", event.getType().name().toLowerCase(), event.getContent(), metadata);
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
            case FINAL_RESPONSE -> TaskHarnessPhase.VERIFY;
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
}
