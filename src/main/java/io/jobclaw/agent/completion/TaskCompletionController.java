package io.jobclaw.agent.completion;

import io.jobclaw.agent.TaskHarnessPhase;
import io.jobclaw.agent.TaskHarnessRun;
import io.jobclaw.agent.TaskHarnessStep;
import io.jobclaw.agent.TaskHarnessSubtask;
import io.jobclaw.agent.TaskHarnessSubtaskStatus;
import io.jobclaw.agent.TaskHarnessVerificationResult;
import io.jobclaw.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TaskCompletionController {

    private static final Set<String> DOCUMENT_TOOLS = Set.of("read_pdf", "read_word", "read_excel", "read_file");
    private static final Set<String> MUTATING_FILE_TOOLS = Set.of("write_file", "edit_file", "append_file");
    private static final Pattern JSON_PATH_PATTERN = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PARAM_PATH_PATTERN = Pattern.compile("path\\s*[=:]\\s*['\"]?([^,'\"\\n}]+)");

    private final Config config;
    private final ActiveExecutionRegistry activeExecutionRegistry;

    public TaskCompletionController(Config config) {
        this(config, new ActiveExecutionRegistry());
    }

    @Autowired
    public TaskCompletionController(Config config, ActiveExecutionRegistry activeExecutionRegistry) {
        this.config = config;
        this.activeExecutionRegistry = activeExecutionRegistry;
    }

    public TaskCompletionState collectState(TaskHarnessRun run,
                                            DoneDefinition doneDefinition,
                                            String finalResponse,
                                            Throwable failure) {
        List<TaskHarnessSubtask> subtasks = run != null ? run.getSubtasks() : List.of();
        int completedSubtasks = (int) subtasks.stream().filter(subtask -> subtask.status() == TaskHarnessSubtaskStatus.COMPLETED).count();
        int failedSubtasks = (int) subtasks.stream().filter(subtask -> subtask.status() == TaskHarnessSubtaskStatus.FAILED).count();
        int pendingSubtasks = run != null ? run.getPendingSubtaskCount() : 0;
        Set<TaskHarnessPhase> phasesCompleted = new LinkedHashSet<>();
        int activeTools = run != null ? activeExecutionRegistry.activeTools(run.getSessionId()) : 0;
        int activeSubagents = run != null ? activeExecutionRegistry.activeSubagents(run.getSessionId()) : 0;
        boolean documentToolUsed = false;
        boolean mutatingFileToolUsed = false;

        if (run != null) {
            for (TaskHarnessStep step : run.getSteps()) {
                phasesCompleted.add(step.phase());
                Object eventType = step.metadata().get("eventType");
                String toolName = stringValue(step.metadata().get("toolName")).toLowerCase(Locale.ROOT);
                if (DOCUMENT_TOOLS.contains(toolName)) {
                    documentToolUsed = true;
                }
                if (MUTATING_FILE_TOOLS.contains(toolName)) {
                    mutatingFileToolUsed = true;
                }
                if ("TOOL_START".equals(eventType)) {
                    activeTools = Math.max(activeTools, 1);
                } else if ("TOOL_END".equals(eventType) || "TOOL_OUTPUT".equals(eventType) || "TOOL_ERROR".equals(eventType)) {
                    activeTools = Math.max(0, activeTools - 1);
                }
            }
        }

        List<String> artifactPaths = resolveArtifactPaths(run, doneDefinition);
        boolean artifactsFound = artifactPaths.stream().allMatch(this::artifactExists);
        TaskHarnessVerificationResult verificationResult = run != null ? run.getLastVerificationResult() : null;
        String failureType = verificationResult != null ? verificationResult.failureType() : null;
        String failureReason = verificationResult != null ? verificationResult.reason() : (failure != null ? failure.getMessage() : null);

        return new TaskCompletionState(
                run != null && run.hasTrackedSubtasks(),
                pendingSubtasks,
                completedSubtasks,
                failedSubtasks,
                activeSubagents,
                activeTools,
                !artifactPaths.isEmpty() && artifactsFound,
                documentToolUsed,
                mutatingFileToolUsed,
                phasesCompleted,
                finalResponse != null && !finalResponse.isBlank() && !finalResponse.startsWith("Error:"),
                isPlanningOnly(finalResponse),
                failureType,
                failureReason,
                artifactPaths
        );
    }

    public TaskCompletionDecision evaluate(TaskHarnessRun run,
                                           DoneDefinition doneDefinition,
                                           String finalResponse,
                                           Throwable failure) {
        TaskCompletionState state = collectState(run, doneDefinition, finalResponse, failure);

        if (failure != null) {
            return TaskCompletionDecision.blocked(
                    state.lastFailureReason() != null ? state.lastFailureReason() : "Unhandled execution failure",
                    List.of("execution_failure")
            );
        }

        if (doneDefinition.requiresWorklist()) {
            if (!state.worklistPlanned()) {
                return TaskCompletionDecision.repair("Worklist task has not planned subtasks", List.of("worklist_not_planned"));
            }
            if (state.pendingSubtasks() > 0) {
                return TaskCompletionDecision.cont("Pending subtasks remain", List.of("pending_subtasks"));
            }
        }

        if (!doneDefinition.requiredArtifacts().isEmpty() && !state.artifactsFound()) {
            return TaskCompletionDecision.repair("Required artifact is missing", List.of("required_artifact_missing"));
        }

        if (doneDefinition.deliveryType() == DeliveryType.DOCUMENT_SUMMARY && !state.documentToolUsed()) {
            return TaskCompletionDecision.repair("Document summary task has not actually read the source document", List.of("document_not_read"));
        }

        if (doneDefinition.deliveryType() == DeliveryType.PATCH && !state.mutatingFileToolUsed()) {
            return TaskCompletionDecision.repair("Patch task has not produced any file mutation evidence", List.of("file_not_mutated"));
        }

        if (state.activeTools() > 0 || state.activeSubagents() > 0) {
            return TaskCompletionDecision.cont("Tools or subagents are still active", List.of("runtime_still_active"));
        }

        if (hasStrongArtifactEvidence(doneDefinition, state)) {
            return TaskCompletionDecision.complete("Artifact evidence satisfied the done definition");
        }

        if (state.hasPlanningOnlyResponse()) {
            return TaskCompletionDecision.repair("Final response still reads like future work", List.of("planning_only_response"));
        }

        if (doneDefinition.requiresFinalSummary() && !state.hasUsableFinalResponse()) {
            return TaskCompletionDecision.repair("Missing usable final response", List.of("usable_final_response"));
        }

        if (shouldHonorVerifierFailure(doneDefinition, state)) {
            return TaskCompletionDecision.repair(
                    state.lastFailureReason() != null ? state.lastFailureReason() : "Verifier rejected the response",
                    List.of(state.lastFailureType())
            );
        }

        return TaskCompletionDecision.complete("Done definition satisfied");
    }

    private boolean isPlanningOnly(String finalResponse) {
        if (finalResponse == null || finalResponse.isBlank()) {
            return false;
        }
        String normalized = finalResponse.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("我先")
                || normalized.contains("接下来")
                || normalized.contains("下一步")
                || normalized.contains("继续处理")
                || normalized.contains("i will")
                || normalized.contains("next i will");
    }

    private List<String> resolveArtifactPaths(TaskHarnessRun run, DoneDefinition doneDefinition) {
        if (!doneDefinition.requiredArtifacts().isEmpty()) {
            return doneDefinition.requiredArtifacts();
        }
        if (run == null || (doneDefinition.deliveryType() != DeliveryType.FILE_ARTIFACT
                && doneDefinition.deliveryType() != DeliveryType.PATCH)) {
            return List.of();
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (TaskHarnessStep step : run.getSteps()) {
            Object eventType = step.metadata().get("eventType");
            if (!"TOOL_START".equals(eventType)) {
                continue;
            }
            String toolName = stringValue(step.metadata().get("toolName")).toLowerCase(Locale.ROOT);
            if (!MUTATING_FILE_TOOLS.contains(toolName)) {
                continue;
            }
            paths.addAll(extractPaths(stringValue(step.metadata().get("request"))));
        }
        return List.copyOf(paths);
    }

    private boolean artifactExists(String path) {
        Path input = Paths.get(path);
        Path resolved = input.isAbsolute() ? input.normalize() : Paths.get(config.getWorkspacePath()).resolve(input).normalize();
        return Files.exists(resolved);
    }

    private List<String> extractPaths(String request) {
        if (request == null || request.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        collectMatches(paths, JSON_PATH_PATTERN.matcher(request));
        collectMatches(paths, PARAM_PATH_PATTERN.matcher(request));
        return List.copyOf(paths);
    }

    private void collectMatches(Set<String> paths, Matcher matcher) {
        while (matcher.find()) {
            String path = matcher.group(1);
            if (path != null && !path.isBlank()) {
                paths.add(path.trim());
            }
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private boolean hasStrongArtifactEvidence(DoneDefinition doneDefinition, TaskCompletionState state) {
        return switch (doneDefinition.deliveryType()) {
            case FILE_ARTIFACT -> state.artifactsFound();
            case PATCH -> state.mutatingFileToolUsed() && !state.artifactPaths().isEmpty() && state.artifactsFound();
            default -> false;
        };
    }

    private boolean shouldHonorVerifierFailure(DoneDefinition doneDefinition, TaskCompletionState state) {
        if (state.lastFailureType() == null || state.lastFailureType().isBlank()) {
            return false;
        }

        return switch (doneDefinition.deliveryType()) {
            case FILE_ARTIFACT, PATCH -> false;
            case BATCH_RESULTS -> state.pendingSubtasks() > 0 || !state.hasUsableFinalResponse();
            case DOCUMENT_SUMMARY -> !state.documentToolUsed() || !state.hasUsableFinalResponse();
            case ANSWER -> !state.hasUsableFinalResponse();
        };
    }
}
