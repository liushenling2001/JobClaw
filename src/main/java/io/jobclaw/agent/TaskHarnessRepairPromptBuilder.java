package io.jobclaw.agent;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.StringJoiner;

@Component
public class TaskHarnessRepairPromptBuilder {
    private static final int SUBTASK_EVIDENCE_LIMIT = 40;

    public String build(TaskHarnessRun run, String originalInput, TaskHarnessFailure failure, int attempt) {
        String evidence = buildStructuredEvidence(run, failure);
        String failureType = resolveFailureType(run, failure);
        String repairGuidance = buildRepairGuidance(run, failureType, failure.kind());

        return """
                Previous attempt did not complete successfully.
                You must repair the task by acting on the failure evidence, not by restating an answer.

                Failure kind: %s
                Failure reason: %s
                Failure type: %s
                Repair attempt: %d

                Repair guidance:
                %s

                Failure evidence:
                %s

                Original task:
                %s
                """.formatted(
                failure.kind(),
                failure.reason(),
                failureType,
                attempt,
                repairGuidance,
                evidence,
                originalInput
        );
    }

    public TaskHarnessFailure classify(TaskHarnessRun run, String finalResponse, Throwable failure) {
        if (failure != null) {
            return new TaskHarnessFailure(
                    TaskHarnessFailureKind.EXECUTION_ERROR,
                    safeMessage(failure.getMessage(), "Unhandled execution error"),
                    safeMessage(failure.toString(), "No exception evidence")
            );
        }

        if (finalResponse == null || finalResponse.isBlank()) {
            return new TaskHarnessFailure(
                    TaskHarnessFailureKind.EMPTY_RESPONSE,
                    "Model returned an empty response",
                    buildStepEvidence(latestStep(run, candidate -> candidate.phase() == TaskHarnessPhase.OBSERVE))
            );
        }

        TaskHarnessStep toolFailure = latestStep(run, step ->
                "TOOL_ERROR".equals(step.metadata().get("eventType")));
        if (toolFailure != null) {
            return new TaskHarnessFailure(
                    TaskHarnessFailureKind.TOOL_FAILURE,
                    "Tool execution failed or caused the run to enter repair",
                    buildStepEvidence(toolFailure)
            );
        }

        if (finalResponse.startsWith("Error:")) {
            return new TaskHarnessFailure(
                    TaskHarnessFailureKind.ERROR_RESPONSE,
                    finalResponse,
                    buildStepEvidence(latestStep(run, candidate ->
                            candidate.phase() == TaskHarnessPhase.REPAIR || "TOOL_ERROR".equals(candidate.metadata().get("eventType"))))
            );
        }

        return new TaskHarnessFailure(
                TaskHarnessFailureKind.OUTCOME_FAILURE,
                "Completion controller requested repair",
                safeMessage(finalResponse, "No final response evidence")
        );
    }

    private String buildStructuredEvidence(TaskHarnessRun run, TaskHarnessFailure failure) {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("Classifier evidence: " + safeMessage(failure.evidence(), "No classifier evidence"));
        if (run != null) {
            joiner.add("Active plan contract:");
            joiner.add("- planningMode=" + run.getPlanningMode());
            if (run.getDoneDefinition() != null) {
                joiner.add("- deliveryType=" + run.getDoneDefinition().deliveryType());
                joiner.add("- requiresWorklist=" + run.getDoneDefinition().requiresWorklist());
                joiner.add("- requiresFinalSummary=" + run.getDoneDefinition().requiresFinalSummary());
            }
            String snapshot = run.planExecutionSnapshot();
            if (snapshot != null && !snapshot.isBlank()) {
                joiner.add("Plan execution snapshot:");
                joiner.add(snapshot);
            }
        }

        TaskHarnessStep toolError = latestStep(run, step -> "TOOL_ERROR".equals(step.metadata().get("eventType")));
        if (toolError != null) {
            joiner.add("Recent tool error:");
            joiner.add(buildToolFailureEvidence(run, toolError));
        }

        TaskHarnessStep toolStart = latestStep(run, step -> "TOOL_START".equals(step.metadata().get("eventType")));
        if (toolStart != null) {
            joiner.add("Recent tool start: " + buildStepEvidence(toolStart));
        }

        TaskHarnessStep finalResponse = latestStep(run, step -> "FINAL_RESPONSE".equals(step.metadata().get("eventType")));
        if (finalResponse != null) {
            joiner.add("Recent final response: " + buildStepEvidence(finalResponse));
        }

        if (run.hasTrackedSubtasks()) {
            StringJoiner subtasks = new StringJoiner("\n");
            List<TaskHarnessSubtask> allSubtasks = run.getSubtasks();
            subtasks.add("Tracked subtasks: total=%d, pending=%d".formatted(
                    allSubtasks.size(),
                    run.getPendingSubtaskCount()
            ));
            allSubtasks.stream().limit(SUBTASK_EVIDENCE_LIMIT).forEach(subtask -> subtasks.add("- %s [%s]".formatted(
                    subtask.id(),
                    subtask.status().name()
            ) + subtaskEvidenceSuffix(subtask)));
            int omitted = allSubtasks.size() - Math.min(allSubtasks.size(), SUBTASK_EVIDENCE_LIMIT);
            if (omitted > 0) {
                subtasks.add("... " + omitted + " more subtasks omitted from repair evidence");
            }
            joiner.add(subtasks.toString());
        }

        return joiner.toString();
    }

    private TaskHarnessStep latestStep(TaskHarnessRun run, Predicate<TaskHarnessStep> predicate) {
        List<TaskHarnessStep> matches = run.getSteps().stream()
                .filter(predicate)
                .sorted(Comparator.comparingInt(TaskHarnessStep::index).reversed())
                .toList();
        return matches.isEmpty() ? null : matches.get(0);
    }

    private String buildStepEvidence(TaskHarnessStep step) {
        if (step == null) {
            return "No matching step evidence";
        }
        StringJoiner joiner = new StringJoiner(", ");
        joiner.add("phase=" + step.phase());
        joiner.add("label=" + step.label());
        joiner.add("detail=" + safeMessage(step.detail(), ""));

        Map<String, Object> metadata = step.metadata();
        appendMetadata(joiner, metadata, "eventType");
        appendMetadata(joiner, metadata, "toolName");
        appendMetadata(joiner, metadata, "toolId");
        appendMetadata(joiner, metadata, "request");
        appendMetadata(joiner, metadata, "reason");
        appendMetadata(joiner, metadata, "failureType");
        appendMetadata(joiner, metadata, "finalResponse");
        return joiner.toString();
    }

    private String buildToolFailureEvidence(TaskHarnessRun run, TaskHarnessStep toolError) {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add(buildStepEvidence(toolError));

        TaskHarnessStep matchingToolStart = latestStep(run, step ->
                "TOOL_START".equals(step.metadata().get("eventType"))
                        && sameTool(step, toolError));
        if (matchingToolStart != null) {
            joiner.add("tool_invocation=" + buildStepEvidence(matchingToolStart));
        }

        TaskHarnessStep matchingToolOutput = latestStep(run, step ->
                "TOOL_OUTPUT".equals(step.metadata().get("eventType"))
                        && sameTool(step, toolError));
        if (matchingToolOutput != null) {
            joiner.add("tool_output=" + buildStepEvidence(matchingToolOutput));
        }

        return joiner.toString();
    }

    private boolean sameTool(TaskHarnessStep left, TaskHarnessStep right) {
        String leftToolId = stringValue(left.metadata().get("toolId"));
        String rightToolId = stringValue(right.metadata().get("toolId"));
        if (!leftToolId.isBlank() && !rightToolId.isBlank()) {
            return leftToolId.equals(rightToolId);
        }

        String leftToolName = stringValue(left.metadata().get("toolName"));
        String rightToolName = stringValue(right.metadata().get("toolName"));
        return !leftToolName.isBlank() && leftToolName.equals(rightToolName);
    }

    private void appendMetadata(StringJoiner joiner, Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value != null && !value.toString().isBlank()) {
            joiner.add(key + "=" + value);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String safeMessage(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String resolveFailureType(TaskHarnessRun run, TaskHarnessFailure failure) {
        TaskHarnessStep completionDecision = latestStep(run, step -> "completion_decision".equals(step.label()));
        if (completionDecision != null) {
            Object missingRequirements = completionDecision.metadata().get("missingRequirements");
            String failureType = failureTypeFromMissingRequirements(missingRequirements);
            if (!failureType.isBlank()) {
                return failureType;
            }
        }
        if (failure != null && failure.kind() != null) {
            return failure.kind().name();
        }
        return "UNKNOWN";
    }

    private String failureTypeFromMissingRequirements(Object missingRequirements) {
        if (missingRequirements == null || missingRequirements.toString().isBlank()) {
            return "";
        }
        String normalized = missingRequirements.toString().toLowerCase();
        if (normalized.contains("failed_subtasks_retryable")) {
            return "FAILED_SUBTASKS_RETRYABLE";
        }
        if (normalized.contains("worklist_not_planned")) {
            return "WORKLIST_NOT_PLANNED";
        }
        if (normalized.contains("pending_subtasks")) {
            return "PENDING_SUBTASKS";
        }
        return "";
    }

    private String buildRepairGuidance(TaskHarnessRun run,
                                       String failureType,
                                       TaskHarnessFailureKind failureKind) {
        return switch (failureType) {
            case "EMPTY_RESPONSE" ->
                "Do not stop at silence. Perform the missing action, verify the result, and return a concrete completion response.";
            case "PENDING_SUBTASKS" ->
                    "Do not end the parent task yet. Continue only the active plan's tracked subtasks. Do not recreate the worklist and do not repeat completed subtasks.";
            case "FAILED_SUBTASKS_RETRYABLE" ->
                    "Retry only the failed subtasks that are marked retryable. Do not repeat completed subtasks. Use the same subtaskId when spawning the retry. If a retried subtask still fails or is not recoverable, keep it failed and produce a final summary that lists the failed item and reason.";
            case "WORKLIST_NOT_PLANNED" ->
                "The active plan explicitly requires a worklist, but no tracked subtasks exist. Create only the missing worklist required by the active plan. Do not reinterpret the original task type, and do not redo completed evidence.";
            case "ERROR_RESPONSE", "EXECUTION_FAILURE", "EXECUTION_ERROR" ->
                "Focus on the runtime failure. Use the tool and error evidence to remove the immediate execution error before continuing.";
            default -> buildGenericGuidance(run, failureKind);
        };
    }

    private String buildGenericGuidance(TaskHarnessRun run, TaskHarnessFailureKind failureKind) {
        if (failureKind == TaskHarnessFailureKind.TOOL_FAILURE) {
            return "Focus on the failing tool invocation. Reuse the recorded request, correct the cause of failure, and confirm the tool succeeds before finishing.";
        }
        TaskHarnessStep latestToolStart = latestStep(run, step -> "TOOL_START".equals(step.metadata().get("eventType")));
        if (latestToolStart != null) {
            return "Use the latest tool evidence to complete the missing task outcome, then verify that the result satisfies the task instead of only describing it.";
        }
        return "Act on the recorded evidence, make the minimal required changes, and verify the actual task outcome before responding.";
    }

    private String subtaskEvidenceSuffix(TaskHarnessSubtask subtask) {
        StringJoiner joiner = new StringJoiner(", ", " (", ")");
        boolean hasEvidence = false;
        Object failureType = subtask.metadata().get("failureType");
        if (failureType != null && !failureType.toString().isBlank()) {
            joiner.add("failureType=" + failureType);
            hasEvidence = true;
        }
        Object retryCount = subtask.metadata().get("retryCount");
        if (retryCount != null && !retryCount.toString().isBlank()) {
            joiner.add("retryCount=" + retryCount);
            hasEvidence = true;
        }
        Object retryable = subtask.metadata().get("retryable");
        if (retryable != null && !retryable.toString().isBlank()) {
            joiner.add("retryable=" + retryable);
            hasEvidence = true;
        }
        if (subtask.summary() != null && !subtask.summary().isBlank()) {
            joiner.add("summary=" + safeMessage(subtask.summary(), ""));
            hasEvidence = true;
        }
        return hasEvidence ? joiner.toString() : "";
    }
}
