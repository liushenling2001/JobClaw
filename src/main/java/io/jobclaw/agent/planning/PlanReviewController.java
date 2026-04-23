package io.jobclaw.agent.planning;

import io.jobclaw.agent.TaskHarnessFailure;
import io.jobclaw.agent.TaskHarnessFailureKind;
import io.jobclaw.agent.TaskHarnessRun;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class PlanReviewController {

    private static final int LARGE_RESPONSE_CHARS = 12_000;
    private static final int LARGE_STEP_EVIDENCE_COUNT = 8;

    public PlanReviewDecision evaluate(TaskHarnessRun run,
                                       TaskHarnessFailure failure,
                                       String lastResponse) {
        if (run == null || run.getPlanExecutionState() == null || run.getPlanExecutionState().isEmpty()) {
            return PlanReviewDecision.keep("No explicit plan is active");
        }
        PlanExecutionStep current = run.getPlanExecutionState().currentStep();
        if (current == null) {
            return PlanReviewDecision.keep("No current plan step");
        }
        if (isFatal(failure)) {
            return PlanReviewDecision.of(
                    PlanReviewAction.BLOCKED,
                    "Runtime failure appears unrecoverable without external intervention",
                    List.of("Stop this run as blocked instead of repeatedly repairing the same failure.")
            );
        }
        if (isContextTooLarge(lastResponse, current)) {
            return PlanReviewDecision.of(
                    PlanReviewAction.FORCE_ARTIFACT,
                    "Current step produced or accumulated too much context",
                    List.of(
                            "Store oversized intermediate content as a process artifact before continuing.",
                            "Use artifact paths or compact summaries in the parent context.",
                            "Do not paste full file contents or long tool outputs into the final response."
                    )
            );
        }
        if (isStepTooLarge(current)) {
            return PlanReviewDecision.of(
                    PlanReviewAction.SPLIT_STEP,
                    "Current step is too broad for stable execution",
                    List.of(
                            "Split the current step into narrower collect/summarize/handoff substeps.",
                            "Complete only the next narrow step before moving on.",
                            "Keep each substep output short and artifact-backed."
                    )
            );
        }
        if (needsIsolation(run, failure, current)) {
            return PlanReviewDecision.of(
                    PlanReviewAction.DELEGATE_STEP,
                    "Current step needs isolated execution rather than parent-context repair",
                    List.of(
                            "Use `spawn` for the current step with only the required paths and constraints.",
                            "The subagent must return a short handoff summary and artifact paths.",
                            "Do not pass the full parent conversation into the subagent."
                    )
            );
        }
        return PlanReviewDecision.keep("Plan is still usable; proceed with normal repair");
    }

    private boolean isFatal(TaskHarnessFailure failure) {
        if (failure == null || failure.kind() != TaskHarnessFailureKind.EXECUTION_ERROR) {
            return false;
        }
        String text = normalize(failure.reason() + " " + failure.evidence());
        return text.contains("401")
                || text.contains("403")
                || text.contains("invalid api key")
                || text.contains("unauthorized")
                || text.contains("permission denied");
    }

    private boolean isContextTooLarge(String lastResponse, PlanExecutionStep current) {
        int responseLength = lastResponse == null ? 0 : lastResponse.length();
        if (responseLength >= LARGE_RESPONSE_CHARS) {
            return true;
        }
        return current.evidence().stream().anyMatch(evidence -> {
            Object artifact = evidence.metadata().get("artifactPath");
            String summary = evidence.summary();
            return artifact != null && !artifact.toString().isBlank()
                    || summary != null && summary.length() > 1000;
        });
    }

    private boolean isStepTooLarge(PlanExecutionStep current) {
        return current.evidence().size() >= LARGE_STEP_EVIDENCE_COUNT;
    }

    private boolean needsIsolation(TaskHarnessRun run, TaskHarnessFailure failure, PlanExecutionStep current) {
        if (run.hasTrackedSubtasks() && run.getPendingSubtaskCount() > 0) {
            return true;
        }
        String text = normalize(current.step().goal() + " " + current.step().completion() + " "
                + (failure != null ? failure.reason() + " " + failure.evidence() : ""));
        return text.contains("多个")
                || text.contains("多文件")
                || text.contains("批量")
                || text.contains("large")
                || text.contains("timeout")
                || text.contains("interrupted")
                || text.contains("context");
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
