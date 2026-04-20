package io.jobclaw.agent.learning;

import io.jobclaw.agent.TaskHarnessRun;
import io.jobclaw.agent.TaskHarnessStep;
import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.experience.ExperienceMemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class LearningCandidateService {

    private final LearningCandidateStore store;
    private final ExperienceMemoryService experienceMemoryService;

    @Autowired
    public LearningCandidateService(LearningCandidateStore store,
                                    ExperienceMemoryService experienceMemoryService) {
        this.store = store;
        this.experienceMemoryService = experienceMemoryService;
    }

    public LearningCandidateService(LearningCandidateStore store) {
        this(store, null);
    }

    public void recordSuccessfulRun(TaskHarnessRun run) {
        if (run == null || !run.isSuccess() || run.getDoneDefinition() == null) {
            return;
        }
        List<String> tools = toolSequence(run);
        if (tools.isEmpty()) {
            return;
        }

        List<LearningCandidate> candidates = new ArrayList<>(store.list());
        if (!hasCandidateForRun(candidates, run.getRunId(), LearningCandidateType.WORKFLOW)
                && isRepeatable(run.getDoneDefinition().deliveryType())) {
            candidates.add(workflowCandidate(run, tools));
        }
        if (!hasCandidateForRun(candidates, run.getRunId(), LearningCandidateType.SKILL_UPDATE)
                && shouldSuggestSkillPromotion(run, tools)) {
            candidates.add(skillPromotionCandidate(run, tools));
        }
        store.saveAll(candidates);
    }

    public void recordFailedRun(TaskHarnessRun run, String reason) {
        if (run == null || run.isSuccess() || run.getDoneDefinition() == null) {
            return;
        }
        List<LearningCandidate> candidates = new ArrayList<>(store.list());
        if (hasCandidateForRun(candidates, run.getRunId(), LearningCandidateType.NEGATIVE_LESSON)) {
            return;
        }
        candidates.add(negativeLessonCandidate(run, reason));
        store.saveAll(candidates);
    }

    public List<LearningCandidate> listPending() {
        return store.list().stream()
                .filter(candidate -> candidate.getStatus() == LearningCandidateStatus.PENDING)
                .toList();
    }

    public List<LearningCandidate> list(String status) {
        if (status == null || status.isBlank()) {
            return store.list();
        }
        LearningCandidateStatus parsedStatus = parseStatus(status);
        return store.list().stream()
                .filter(candidate -> candidate.getStatus() == parsedStatus)
                .toList();
    }

    public Optional<LearningCandidate> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return store.list().stream()
                .filter(candidate -> id.equals(candidate.getId()))
                .findFirst();
    }

    public Optional<LearningCandidate> markAccepted(String id) {
        Optional<LearningCandidate> accepted = updateStatus(id, LearningCandidateStatus.ACCEPTED);
        accepted.ifPresent(candidate -> {
            if (experienceMemoryService != null) {
                experienceMemoryService.applyAcceptedCandidate(candidate);
            }
        });
        return accepted;
    }

    public Optional<LearningCandidate> markRejected(String id) {
        return updateStatus(id, LearningCandidateStatus.REJECTED);
    }

    private Optional<LearningCandidate> updateStatus(String id, LearningCandidateStatus status) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        List<LearningCandidate> candidates = new ArrayList<>(store.list());
        for (LearningCandidate candidate : candidates) {
            if (id.equals(candidate.getId())) {
                candidate.setStatus(status);
                candidate.setUpdatedAt(Instant.now());
                store.saveAll(candidates);
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private LearningCandidateStatus parseStatus(String status) {
        try {
            return LearningCandidateStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported learning candidate status: " + status);
        }
    }

    private LearningCandidate workflowCandidate(TaskHarnessRun run, List<String> tools) {
        LearningCandidate candidate = baseCandidate(run, LearningCandidateType.WORKFLOW);
        candidate.setTitle("可复用任务流程候选");
        candidate.setReason("任务已成功完成，并包含可复用工具序列。先记录为候选，不自动改变运行逻辑。");
        candidate.setProposal(buildWorkflowProposal(run, tools));
        candidate.setTags(List.of("workflow", run.getPlanningMode().name(), run.getDoneDefinition().deliveryType().name()));
        candidate.setConfidence(run.hasTrackedSubtasks() ? 0.65 : 0.5);
        candidate.setMetadata(Map.of(
                "toolSequence", tools,
                "hasTrackedSubtasks", run.hasTrackedSubtasks(),
                "pendingSubtasks", run.getPendingSubtaskCount()
        ));
        return candidate;
    }

    private LearningCandidate skillPromotionCandidate(TaskHarnessRun run, List<String> tools) {
        LearningCandidate candidate = baseCandidate(run, LearningCandidateType.SKILL_UPDATE);
        candidate.setTitle("可升级为 skill 的成功流程候选");
        candidate.setReason("该任务类型有明确工具序列和产出形态，可在多次成功后由用户确认固化为 skill。");
        candidate.setProposal(buildSkillProposal(run, tools));
        candidate.setTags(List.of("skill_candidate", run.getDoneDefinition().deliveryType().name()));
        candidate.setConfidence(0.45);
        candidate.setMetadata(Map.of("toolSequence", tools));
        return candidate;
    }

    private LearningCandidate negativeLessonCandidate(TaskHarnessRun run, String reason) {
        TaskHarnessStep lastStep = run.getSteps().isEmpty() ? null : run.getSteps().get(run.getSteps().size() - 1);
        LearningCandidate candidate = baseCandidate(run, LearningCandidateType.NEGATIVE_LESSON);
        candidate.setTitle("失败经验候选");
        candidate.setReason("任务未成功完成。记录为负向经验候选，供每日经验整理和人工确认，避免重复错误流程。");
        candidate.setProposal(buildNegativeLessonProposal(run, reason, lastStep));
        candidate.setTags(List.of("negative_lesson", run.getPlanningMode().name(), run.getDoneDefinition().deliveryType().name()));
        candidate.setConfidence(run.hasTrackedSubtasks() ? 0.65 : 0.5);
        candidate.setMetadata(Map.of(
                "failureReason", reason != null ? reason : "",
                "repairAttempts", run.getRepairAttempts(),
                "pendingSubtasks", run.getPendingSubtaskCount(),
                "toolSequence", toolSequence(run),
                "lastStepLabel", lastStep != null ? lastStep.label() : "",
                "lastStepDetail", lastStep != null ? truncate(lastStep.detail(), 500) : ""
        ));
        return candidate;
    }

    private LearningCandidate baseCandidate(TaskHarnessRun run, LearningCandidateType type) {
        Instant now = Instant.now();
        LearningCandidate candidate = new LearningCandidate();
        candidate.setId(UUID.randomUUID().toString());
        candidate.setType(type);
        candidate.setStatus(LearningCandidateStatus.PENDING);
        candidate.setSessionId(run.getSessionId());
        candidate.setSourceRunId(run.getRunId());
        candidate.setTaskInput(run.getTaskInput());
        candidate.setPlanningMode(run.getPlanningMode());
        candidate.setDeliveryType(run.getDoneDefinition().deliveryType());
        candidate.setCreatedAt(now);
        candidate.setUpdatedAt(now);
        return candidate;
    }

    private String buildWorkflowProposal(TaskHarnessRun run, List<String> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(run.getTaskInput()).append("\n");
        sb.append("Planning mode: ").append(run.getPlanningMode()).append("\n");
        sb.append("Delivery type: ").append(run.getDoneDefinition().deliveryType()).append("\n");
        sb.append("Suggested tool sequence: ").append(String.join(" -> ", tools)).append("\n");
        if (run.hasTrackedSubtasks()) {
            sb.append("Subtask pattern: tracked independent subtasks, total=")
                    .append(run.getSubtasks().size()).append("\n");
        }
        return sb.toString();
    }

    private String buildSkillProposal(TaskHarnessRun run, List<String> tools) {
        return "After repeated user-approved success, consider creating a skill for tasks similar to:\n"
                + run.getTaskInput()
                + "\nMinimal skill should describe when to use it, expected inputs, tool sequence, and output format.\n"
                + "Observed tools: " + String.join(" -> ", tools);
    }

    private String buildNegativeLessonProposal(TaskHarnessRun run, String reason, TaskHarnessStep lastStep) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task failed and should not be blindly repeated.\n");
        sb.append("Task: ").append(run.getTaskInput()).append("\n");
        sb.append("Planning mode: ").append(run.getPlanningMode()).append("\n");
        sb.append("Delivery type: ").append(run.getDoneDefinition().deliveryType()).append("\n");
        sb.append("Repair attempts: ").append(run.getRepairAttempts()).append("\n");
        sb.append("Pending subtasks: ").append(run.getPendingSubtaskCount()).append("\n");
        if (reason != null && !reason.isBlank()) {
            sb.append("Failure reason: ").append(reason).append("\n");
        }
        if (run.getLastFailure() != null) {
            sb.append("Failure kind: ").append(run.getLastFailure().kind()).append("\n");
            sb.append("Failure detail: ").append(run.getLastFailure().reason()).append("\n");
        }
        List<String> tools = toolSequence(run);
        if (!tools.isEmpty()) {
            sb.append("Observed tools: ").append(String.join(" -> ", tools)).append("\n");
        }
        if (lastStep != null) {
            sb.append("Last step: ").append(lastStep.label()).append(" / ").append(truncate(lastStep.detail(), 500)).append("\n");
        }
        sb.append("Candidate rule: next similar task should check this failure before selecting workflow guidance.");
        return sb.toString();
    }

    private boolean shouldSuggestSkillPromotion(TaskHarnessRun run, List<String> tools) {
        DeliveryType deliveryType = run.getDoneDefinition().deliveryType();
        if (deliveryType == DeliveryType.ANSWER) {
            return false;
        }
        return tools.size() >= 2 && (run.hasTrackedSubtasks()
                || deliveryType == DeliveryType.FILE_ARTIFACT
                || deliveryType == DeliveryType.PATCH
                || deliveryType == DeliveryType.DOCUMENT_SUMMARY);
    }

    private boolean isRepeatable(DeliveryType deliveryType) {
        return deliveryType != DeliveryType.ANSWER;
    }

    private boolean hasCandidateForRun(List<LearningCandidate> candidates,
                                       String runId,
                                       LearningCandidateType type) {
        return candidates.stream().anyMatch(candidate ->
                type == candidate.getType() && runId != null && runId.equals(candidate.getSourceRunId()));
    }

    private List<String> toolSequence(TaskHarnessRun run) {
        Set<String> sequence = new LinkedHashSet<>();
        for (TaskHarnessStep step : run.getSteps()) {
            Object eventType = step.metadata().get("eventType");
            if (!"TOOL_START".equals(eventType)) {
                continue;
            }
            Object toolName = step.metadata().get("toolName");
            if (toolName != null && !toolName.toString().isBlank()) {
                sequence.add(toolName.toString());
            }
        }
        return List.copyOf(sequence);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n[truncated]";
    }
}
