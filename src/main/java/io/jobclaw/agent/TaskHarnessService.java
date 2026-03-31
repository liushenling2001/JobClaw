package io.jobclaw.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
public class TaskHarnessService {

    private final ConcurrentHashMap<String, TaskHarnessRun> runs = new ConcurrentHashMap<>();

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

    public TaskHarnessRun getRun(String runId) {
        return runs.get(runId);
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
}
