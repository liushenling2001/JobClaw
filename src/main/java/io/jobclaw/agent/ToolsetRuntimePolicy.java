package io.jobclaw.agent;

import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.completion.DoneDefinition;
import io.jobclaw.agent.planning.PlanExecutionState;
import io.jobclaw.agent.planning.PlanExecutionStep;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Adds tools required by the current plan step. This keeps dynamic tool loading
 * tied to runtime state rather than growing prompt keyword rules.
 */
@Component
public class ToolsetRuntimePolicy {

    private static final Set<String> BASE_TOOLS = Set.of("memory", "skills", "context_ref", "run_command", "exec", "write_file");
    private static final Set<String> FILE_READ_TOOLS = Set.of(
            "list_dir", "read_file", "read_pdf", "read_word", "read_excel"
    );
    private static final Set<String> FILE_WRITE_TOOLS = Set.of(
            "write_file", "edit_file", "append_file"
    );
    private static final Set<String> WORKLIST_TOOLS = Set.of("subtasks", "spawn");
    private static final Set<String> EXECUTION_TOOLS = Set.of("run_command", "exec");

    public Set<String> requiredToolsFor(TaskHarnessRun run) {
        LinkedHashSet<String> required = new LinkedHashSet<>(BASE_TOOLS);
        if (run == null) {
            return required;
        }

        DoneDefinition done = run.getDoneDefinition();
        DeliveryType deliveryType = done != null ? done.deliveryType() : null;
        PlanExecutionState state = run.getPlanExecutionState();
        PlanExecutionStep current = state != null ? state.currentStep() : null;
        String stepId = current != null ? normalize(current.step().id()) : "";

        if (deliveryType == DeliveryType.DOCUMENT_SUMMARY) {
            required.addAll(FILE_READ_TOOLS);
        }
        if (deliveryType == DeliveryType.FILE_ARTIFACT || deliveryType == DeliveryType.PATCH) {
            required.addAll(FILE_READ_TOOLS);
            required.addAll(FILE_WRITE_TOOLS);
            required.addAll(EXECUTION_TOOLS);
        }
        if (deliveryType == DeliveryType.BATCH_RESULTS || run.hasTrackedSubtasks()) {
            required.addAll(FILE_READ_TOOLS);
            required.addAll(WORKLIST_TOOLS);
        }

        if (stepId.isBlank()) {
            return required;
        }
        if (stepId.contains("inspect") || stepId.contains("collect") || stepId.contains("read-target")) {
            required.addAll(FILE_READ_TOOLS);
        }
        if (stepId.contains("process") || stepId.contains("source") || stepId.contains("summarize")) {
            required.addAll(FILE_READ_TOOLS);
            required.add("context_ref");
        }
        if (stepId.contains("worklist") || stepId.contains("subtask") || stepId.contains("delegate")) {
            required.addAll(WORKLIST_TOOLS);
            required.addAll(FILE_READ_TOOLS);
        }
        if (stepId.contains("update") || stepId.contains("produce") || stepId.contains("artifact")
                || stepId.contains("target") || stepId.contains("write") || stepId.contains("handoff")) {
            required.addAll(FILE_READ_TOOLS);
            required.addAll(FILE_WRITE_TOOLS);
            required.addAll(EXECUTION_TOOLS);
        }
        if (stepId.contains("verify") || stepId.contains("finish")) {
            required.addAll(FILE_READ_TOOLS);
        }
        return required;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
