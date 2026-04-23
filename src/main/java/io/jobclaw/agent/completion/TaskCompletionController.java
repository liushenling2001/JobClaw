package io.jobclaw.agent.completion;

import io.jobclaw.agent.TaskHarnessPhase;
import io.jobclaw.agent.TaskHarnessRun;
import io.jobclaw.agent.TaskHarnessStep;
import io.jobclaw.agent.TaskHarnessSubtask;
import io.jobclaw.agent.TaskHarnessSubtaskStatus;
import io.jobclaw.agent.planning.PlanStepStatus;
import io.jobclaw.agent.planning.TaskPlanningMode;
import io.jobclaw.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
    private static final Set<String> RETRYABLE_SUBTASK_FAILURE_TYPES = Set.of(
            "timeout", "interrupted", "transient_model_error", "child_error"
    );
    private static final Pattern JSON_PATH_PATTERN = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PARAM_PATH_PATTERN = Pattern.compile("path\\s*[=:]\\s*['\"]?([^,'\"\\n}]+)");
    private static final Pattern WINDOWS_ARTIFACT_PATH_PATTERN = Pattern.compile(
            "(?i)([A-Z]:\\\\[^\\r\\n\"'<>|]+?\\.(?:docx|doc|pdf|xlsx|xls|pptx|txt|md|html))"
    );
    private static final Pattern POSIX_ARTIFACT_PATH_PATTERN = Pattern.compile(
            "(?i)(/[^\\r\\n\"'<>|]+?\\.(?:docx|doc|pdf|xlsx|xls|pptx|txt|md|html))"
    );

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
        int incompletePlanSteps = 0;

        if (run != null) {
            if (run.getPlanExecutionState() != null && !run.getPlanExecutionState().isEmpty()) {
                incompletePlanSteps = (int) run.getPlanExecutionState().steps().stream()
                        .filter(step -> step.status() != PlanStepStatus.COMPLETED)
                        .count();
            }
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
        boolean artifactsFound = artifactsFound(artifactPaths, doneDefinition);
        String failureType = failure != null ? failure.getClass().getSimpleName() : null;
        String failureReason = failure != null ? failure.getMessage() : null;

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
                incompletePlanSteps,
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

        if (state.worklistPlanned() && state.pendingSubtasks() > 0) {
            return TaskCompletionDecision.cont("Pending subtasks remain", List.of("pending_subtasks"));
        }

        if (state.worklistPlanned() && hasRetryableFailedSubtask(run)) {
            return TaskCompletionDecision.repair(
                    "Retryable failed subtasks remain",
                    List.of("failed_subtasks_retryable")
            );
        }

        if (doneDefinition.requiresWorklist() && !state.worklistPlanned()) {
            return TaskCompletionDecision.planReview("Active plan requires a worklist but no subtasks are tracked", List.of("worklist_not_planned"));
        }

        if (!doneDefinition.requiredArtifacts().isEmpty() && !state.artifactsFound()) {
            return TaskCompletionDecision.planReview("Required artifact is missing from the planned output location", List.of("required_artifact_missing"));
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

        if (doneDefinition.planningMode() != TaskPlanningMode.DIRECT && state.incompletePlanSteps() > 0) {
            return TaskCompletionDecision.cont("Plan still has unfinished steps", List.of("unfinished_plan_steps"));
        }

        if (state.hasPlanningOnlyResponse()) {
            return doneDefinition.planningMode() == TaskPlanningMode.DIRECT
                    ? TaskCompletionDecision.repair("Final response still reads like future work", List.of("planning_only_response"))
                    : TaskCompletionDecision.cont("Plan response indicates more work remains", List.of("planning_only_response"));
        }

        if (doneDefinition.requiresFinalSummary() && !state.hasUsableFinalResponse()) {
            return TaskCompletionDecision.repair("Missing usable final response", List.of("usable_final_response"));
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
            String toolName = stringValue(step.metadata().get("toolName")).toLowerCase(Locale.ROOT);
            if ("TOOL_START".equals(eventType) && MUTATING_FILE_TOOLS.contains(toolName)) {
                paths.addAll(extractPaths(stringValue(step.metadata().get("request"))));
            }
            if ("TOOL_OUTPUT".equals(eventType) || "TOOL_END".equals(eventType)) {
                paths.addAll(extractArtifactPathsFromText(step.detail()));
                paths.addAll(extractArtifactPathsFromText(stringValue(step.metadata().get("output"))));
                paths.addAll(extractArtifactPathsFromText(stringValue(step.metadata().get("result"))));
            }
        }
        return List.copyOf(paths);
    }

    private boolean artifactExists(String path) {
        try {
            Path input = Paths.get(path);
            Path resolved = input.isAbsolute() ? input.normalize() : Paths.get(config.getWorkspacePath()).resolve(input).normalize();
            return Files.exists(resolved);
        } catch (InvalidPathException | SecurityException e) {
            return false;
        }
    }

    private boolean artifactsFound(List<String> artifactPaths, DoneDefinition doneDefinition) {
        if (artifactPaths.isEmpty()) {
            return false;
        }
        List<Path> requiredDirectories = requiredArtifactDirectories(doneDefinition);
        if (requiredDirectories.isEmpty()) {
            return artifactPaths.stream().allMatch(this::artifactExists);
        }
        return artifactPaths.stream()
                .map(this::safeResolvePath)
                .anyMatch(path -> path != null
                        && Files.exists(path)
                        && requiredDirectories.stream().anyMatch(directory -> isUnder(path, directory)));
    }

    private List<Path> requiredArtifactDirectories(DoneDefinition doneDefinition) {
        if (doneDefinition == null || doneDefinition.requiredArtifactDirectories().isEmpty()) {
            return List.of();
        }
        List<Path> directories = new ArrayList<>();
        for (String directory : doneDefinition.requiredArtifactDirectories()) {
            Path resolved = safeResolvePath(directory);
            if (resolved != null) {
                directories.add(resolved);
            }
        }
        return List.copyOf(directories);
    }

    private Path safeResolvePath(String path) {
        try {
            Path input = Paths.get(path);
            return input.isAbsolute()
                    ? input.normalize()
                    : Paths.get(config.getWorkspacePath()).resolve(input).normalize();
        } catch (InvalidPathException | SecurityException e) {
            return null;
        }
    }

    private boolean isUnder(Path path, Path directory) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        return normalizedPath.startsWith(normalizedDirectory);
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

    private List<String> extractArtifactPathsFromText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        collectArtifactMatches(paths, WINDOWS_ARTIFACT_PATH_PATTERN.matcher(text));
        collectArtifactMatches(paths, POSIX_ARTIFACT_PATH_PATTERN.matcher(text));
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

    private void collectArtifactMatches(Set<String> paths, Matcher matcher) {
        while (matcher.find()) {
            String path = matcher.group(1);
            if (path != null && !path.isBlank()) {
                String cleaned = cleanArtifactPath(path);
                if (isUsableArtifactPath(cleaned)) {
                    paths.add(cleaned);
                }
            }
        }
    }

    private String cleanArtifactPath(String path) {
        String cleaned = path.trim();
        int escapedNewline = cleaned.indexOf("\\n");
        if (escapedNewline >= 0) {
            cleaned = cleaned.substring(0, escapedNewline).trim();
        }
        int newline = firstIndexOf(cleaned, '\r', '\n');
        if (newline >= 0) {
            cleaned = cleaned.substring(0, newline).trim();
        }
        while (!cleaned.isEmpty() && ",.;:，。；：)）]】".indexOf(cleaned.charAt(cleaned.length() - 1)) >= 0) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private boolean isUsableArtifactPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains(" 的目录") || lower.contains(" directory of ")) {
            return false;
        }
        if (normalized.contains("\\n") || normalized.contains("\n") || normalized.contains("\r")) {
            return false;
        }
        try {
            Paths.get(normalized);
            return true;
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private int firstIndexOf(String value, char... chars) {
        int found = -1;
        for (char c : chars) {
            int index = value.indexOf(c);
            if (index >= 0 && (found < 0 || index < found)) {
                found = index;
            }
        }
        return found;
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private boolean hasRetryableFailedSubtask(TaskHarnessRun run) {
        if (run == null) {
            return false;
        }
        int maxRetries = Math.max(0, config.getAgent().getMaxSubtaskRepairAttempts());
        if (maxRetries <= 0) {
            return false;
        }
        return run.getSubtasks().stream()
                .filter(subtask -> subtask.status() == TaskHarnessSubtaskStatus.FAILED)
                .anyMatch(subtask -> isRetryable(subtask) && retryCount(subtask) <= maxRetries);
    }

    private boolean isRetryable(TaskHarnessSubtask subtask) {
        Object retryable = subtask.metadata().get("retryable");
        if (retryable instanceof Boolean bool) {
            return bool;
        }
        String failureType = stringValue(subtask.metadata().get("failureType")).toLowerCase(Locale.ROOT);
        return RETRYABLE_SUBTASK_FAILURE_TYPES.contains(failureType);
    }

    private int retryCount(TaskHarnessSubtask subtask) {
        Object value = subtask.metadata().get("retryCount");
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private boolean hasStrongArtifactEvidence(DoneDefinition doneDefinition, TaskCompletionState state) {
        return switch (doneDefinition.deliveryType()) {
            case FILE_ARTIFACT -> state.artifactsFound();
            case PATCH -> state.mutatingFileToolUsed() && !state.artifactPaths().isEmpty() && state.artifactsFound();
            default -> false;
        };
    }

}
