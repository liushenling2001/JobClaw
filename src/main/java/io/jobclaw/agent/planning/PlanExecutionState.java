package io.jobclaw.agent.planning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PlanExecutionState {

    private final List<PlanExecutionStep> steps;
    private final Map<String, PlanExecutionStep> byId;

    public PlanExecutionState(List<PlanStep> planSteps) {
        this.steps = new ArrayList<>();
        this.byId = new LinkedHashMap<>();
        if (planSteps != null) {
            for (PlanStep step : planSteps) {
                if (step == null || step.id().isBlank()) {
                    continue;
                }
                PlanExecutionStep executionStep = new PlanExecutionStep(step);
                steps.add(executionStep);
                byId.put(step.id(), executionStep);
            }
        }
    }

    public synchronized boolean isEmpty() {
        return steps.isEmpty();
    }

    public synchronized List<PlanExecutionStep> steps() {
        return List.copyOf(steps);
    }

    public synchronized String currentStepId() {
        return steps.stream()
                .filter(step -> step.status() == PlanStepStatus.RUNNING)
                .findFirst()
                .or(() -> steps.stream().filter(step -> step.status() == PlanStepStatus.PENDING).findFirst())
                .map(step -> step.step().id())
                .orElse("");
    }

    public synchronized PlanExecutionStep currentStep() {
        return steps.stream()
                .filter(step -> step.status() == PlanStepStatus.RUNNING)
                .findFirst()
                .or(() -> steps.stream().filter(step -> step.status() == PlanStepStatus.PENDING).findFirst())
                .orElse(null);
    }

    public synchronized void startCurrentStep() {
        PlanExecutionStep step = currentStep();
        if (step != null) {
            step.markRunning();
        }
    }

    public synchronized void completeCurrentStep(String summary, Map<String, Object> metadata, String artifactPath) {
        PlanExecutionStep step = currentStep();
        if (step == null) {
            return;
        }
        step.markCompleted();
        step.addEvidence(new StepEvidence("step", concise(summary, 300), safeMetadata(metadata), artifactPath));
    }

    public synchronized void failCurrentStep(String summary, Map<String, Object> metadata) {
        PlanExecutionStep step = currentStep();
        if (step == null) {
            return;
        }
        step.markFailed();
        step.addEvidence(new StepEvidence("step_failure", concise(summary, 300), safeMetadata(metadata), null));
    }

    public synchronized boolean splitCurrentStep() {
        PlanExecutionStep current = currentStep();
        if (current == null) {
            return false;
        }
        int index = steps.indexOf(current);
        if (index < 0) {
            return false;
        }
        String baseId = current.step().id();
        if (baseId.endsWith("-collect") || baseId.endsWith("-summarize") || baseId.endsWith("-handoff")) {
            return false;
        }
        List<PlanExecutionStep> replacements = List.of(
                new PlanExecutionStep(new PlanStep(
                        baseId + "-collect",
                        "收集当前步骤所需的最小输入和路径",
                        "只保留路径、约束和必要片段，避免带入全量上下文"
                )),
                new PlanExecutionStep(new PlanStep(
                        baseId + "-summarize",
                        "隔离处理当前步骤的主要内容",
                        "形成简短结构化要点，超长内容必须保存为 process artifact"
                )),
                new PlanExecutionStep(new PlanStep(
                        baseId + "-handoff",
                        "生成当前步骤的交接结果",
                        "输出可供下一步使用的摘要和 artifact 路径"
                ))
        );
        byId.remove(baseId);
        steps.remove(index);
        steps.addAll(index, replacements);
        for (PlanExecutionStep replacement : replacements) {
            byId.put(replacement.step().id(), replacement);
        }
        replacements.get(0).markRunning();
        replacements.get(0).addEvidence(new StepEvidence(
                "plan_review",
                "Plan review split oversized step " + baseId,
                Map.of("originalStepId", baseId),
                null
        ));
        return true;
    }

    public synchronized String currentStepContract() {
        PlanExecutionStep step = currentStep();
        if (step == null) {
            return "";
        }
        return """
                [Current Plan Step]
                stepId: %s
                goal: %s
                doneWhen: %s

                Execute only this step. Do not restart completed steps. Do not output a final answer unless this is the final plan step.
                Store large intermediate findings as process artifacts and return only concise handoff evidence.
                """.formatted(step.step().id(), step.step().goal(), step.step().completion());
    }

    public synchronized void recordToolEvent(String eventType, String toolName, String content, Map<String, Object> metadata) {
        if (steps.isEmpty()) {
            return;
        }
        PlanExecutionStep step = resolveStepForTool(toolName);
        if (step == null) {
            step = currentOrFirstPending();
        }
        if (step == null) {
            return;
        }
        if ("TOOL_ERROR".equals(eventType)) {
            step.markFailed();
        } else if ("TOOL_START".equals(eventType)) {
            step.markRunning();
        } else if ("TOOL_END".equals(eventType) || "TOOL_OUTPUT".equals(eventType)) {
            step.markRunning();
            if (isCompletionEvidence(toolName)) {
                step.markCompleted();
            }
        }
        step.addEvidence(new StepEvidence("tool", concise(content, 220), safeMetadata(metadata), null));
    }

    public synchronized void recordSubtaskEvidence(String status, String content, Map<String, Object> metadata) {
        if (steps.isEmpty()) {
            return;
        }
        PlanExecutionStep step = currentOrFirstPending();
        if (step == null) {
            return;
        }
        step.markRunning();
        if ("success".equalsIgnoreCase(status) || "completed".equalsIgnoreCase(status)) {
            step.markCompleted();
        }
        step.addEvidence(new StepEvidence("subtask", concise(content, 220), safeMetadata(metadata), null));
    }

    public synchronized void markCompletedByArtifactEvidence() {
        PlanExecutionStep target = byId.get("update-target");
        if (target == null) {
            target = byId.get("produce-artifact");
        }
        if (target != null) {
            target.markCompleted();
        }
    }

    public synchronized String snapshot() {
        if (steps.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[Plan Execution State]\n");
        String current = currentStepId();
        if (!current.isBlank()) {
            sb.append("Current step: ").append(current).append("\n");
        }
        sb.append("Steps:\n");
        for (PlanExecutionStep step : steps) {
            sb.append("- ")
                    .append(step.step().id())
                    .append(" [")
                    .append(step.status())
                    .append("]: ")
                    .append(step.step().goal());
            List<StepEvidence> evidence = step.evidence();
            if (!evidence.isEmpty()) {
                sb.append(" | evidence: ");
                sb.append(evidence.stream()
                        .skip(Math.max(0, evidence.size() - 2))
                        .map(StepEvidence::summary)
                        .filter(value -> value != null && !value.isBlank())
                        .reduce((a, b) -> a + " ; " + b)
                        .orElse(""));
                String artifact = evidence.stream()
                        .skip(Math.max(0, evidence.size() - 2))
                        .map(StepEvidence::artifactPath)
                        .filter(value -> value != null && !value.isBlank())
                        .reduce((a, b) -> a + " ; " + b)
                        .orElse("");
                if (!artifact.isBlank()) {
                    sb.append(" | artifacts: ").append(artifact);
                }
            }
            sb.append("\n");
        }
        sb.append("Use this state instead of replaying full prior tool outputs. Do not repeat completed steps unless the plan needs a targeted outcome check.");
        return sb.toString();
    }

    private PlanExecutionStep currentOrFirstPending() {
        return steps.stream()
                .filter(step -> step.status() == PlanStepStatus.RUNNING)
                .findFirst()
                .or(() -> steps.stream().filter(step -> step.status() == PlanStepStatus.PENDING).findFirst())
                .orElse(null);
    }

    private PlanExecutionStep resolveStepForTool(String toolName) {
        String normalized = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        if (normalized.equals("list_dir")) {
            return firstExisting("inspect-inputs");
        }
        if (normalized.startsWith("read_") || normalized.equals("context_ref")) {
            return firstExisting("process-sources", "read-target", "inspect-inputs");
        }
        if (normalized.equals("write_file") || normalized.equals("edit_file") || normalized.equals("append_file")) {
            return firstExisting("update-target", "produce-artifact");
        }
        if (normalized.equals("spawn") || normalized.equals("subtasks")) {
            return firstExisting("process-sources", "execute-worklist");
        }
        return null;
    }

    private PlanExecutionStep firstExisting(String... ids) {
        for (String id : ids) {
            PlanExecutionStep step = byId.get(id);
            if (step != null) {
                return step;
            }
        }
        return null;
    }

    private boolean isCompletionEvidence(String toolName) {
        String normalized = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        return normalized.equals("list_dir")
                || normalized.startsWith("read_")
                || normalized.equals("context_ref")
                || normalized.equals("write_file")
                || normalized.equals("edit_file")
                || normalized.equals("append_file")
                || normalized.equals("spawn");
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

    private Map<String, Object> safeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text) {
                copy.put(entry.getKey(), concise(text, 300));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }
}
