package io.jobclaw.agent;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.StringJoiner;

@Component
public class TaskHarnessRepairPromptBuilder {

    public String build(TaskHarnessRun run, String originalInput, TaskHarnessFailure failure, int attempt) {
        String evidence = buildStructuredEvidence(run, failure);
        String verifierFailureType = resolveVerifierFailureType(run);
        String repairGuidance = buildRepairGuidance(run, verifierFailureType, failure.kind());

        return """
                Previous attempt did not complete successfully.
                You must repair the task by acting on the failure evidence, not by restating an answer.

                Failure kind: %s
                Failure reason: %s
                Verifier failure type: %s
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
                verifierFailureType,
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
                    mapVerificationFailureKind(run, TaskHarnessFailureKind.ERROR_RESPONSE),
                    finalResponse,
                    buildStepEvidence(latestStep(run, candidate ->
                            candidate.phase() == TaskHarnessPhase.REPAIR || "TOOL_ERROR".equals(candidate.metadata().get("eventType"))))
            );
        }

        return new TaskHarnessFailure(
                mapVerificationFailureKind(run, TaskHarnessFailureKind.VERIFICATION_FAILURE),
                "Verifier rejected the final response",
                safeMessage(finalResponse, "No final response evidence")
        );
    }

    private String buildStructuredEvidence(TaskHarnessRun run, TaskHarnessFailure failure) {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("Classifier evidence: " + safeMessage(failure.evidence(), "No classifier evidence"));

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

        TaskHarnessStep verifyFailure = latestStep(run, step -> "verify_failed".equals(step.label()));
        if (verifyFailure != null) {
            joiner.add("Verification failure:");
            joiner.add(buildVerificationEvidence(verifyFailure));
        }

        if (run.hasTrackedSubtasks()) {
            StringJoiner subtasks = new StringJoiner("\n");
            subtasks.add("Tracked subtasks:");
            run.getSubtasks().forEach(subtask -> subtasks.add("- %s [%s]".formatted(
                    subtask.id(),
                    subtask.status().name()
            )));
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

    private String buildVerificationEvidence(TaskHarnessStep verifyFailure) {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add(buildStepEvidence(verifyFailure));
        Object finalResponse = verifyFailure.metadata().get("finalResponse");
        if (finalResponse != null && !finalResponse.toString().isBlank()) {
            joiner.add("verifier_final_response=" + finalResponse);
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

    private String resolveVerifierFailureType(TaskHarnessRun run) {
        TaskHarnessVerificationResult verificationResult = run.getLastVerificationResult();
        if (verificationResult != null && verificationResult.failureType() != null
                && !verificationResult.failureType().isBlank()) {
            return verificationResult.failureType();
        }
        TaskHarnessStep verifyFailure = latestStep(run, step -> "verify_failed".equals(step.label()));
        if (verifyFailure != null) {
            Object failureType = verifyFailure.metadata().get("failureType");
            if (failureType != null && !failureType.toString().isBlank()) {
                return failureType.toString();
            }
        }
        return "UNKNOWN";
    }

    private TaskHarnessFailureKind mapVerificationFailureKind(TaskHarnessRun run, TaskHarnessFailureKind fallback) {
        String failureType = resolveVerifierFailureType(run);
        return switch (failureType) {
            case "EXECUTION_FAILURE" -> TaskHarnessFailureKind.EXECUTION_ERROR;
            case "EMPTY_RESPONSE" -> TaskHarnessFailureKind.EMPTY_RESPONSE;
            case "ERROR_RESPONSE" -> TaskHarnessFailureKind.ERROR_RESPONSE;
            case "TEST_COMMAND", "FILE_EXPECTATION", "COMMAND_EXIT" -> TaskHarnessFailureKind.VERIFICATION_FAILURE;
            default -> fallback;
        };
    }

    private String buildRepairGuidance(TaskHarnessRun run,
                                       String verifierFailureType,
                                       TaskHarnessFailureKind failureKind) {
        return switch (verifierFailureType) {
            case "TEST_COMMAND" ->
                    "Focus on the failing test command. Identify the code or config issue, change the relevant files, then rerun the same test command to produce passing evidence.";
            case "FILE_EXPECTATION" ->
                    "Focus on the missing or unchanged file. Use the recorded file path and write/edit the file so the expected artifact exists with the intended content.";
            case "COMMAND_EXIT" ->
                    "Focus on the non-zero command exit. Inspect the command request, fix the underlying cause, and rerun the command until the output no longer reports a non-zero exit.";
            case "EMPTY_RESPONSE" ->
                    "Do not stop at silence. Perform the missing action, verify the result, and return a concrete completion response.";
            case "PENDING_SUBTASKS" ->
                    "Do not end the parent task yet. Continue the planned worklist, execute the remaining subtasks, and mark each one complete or failed before finishing.";
            case "ERROR_RESPONSE", "EXECUTION_FAILURE" ->
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
}
