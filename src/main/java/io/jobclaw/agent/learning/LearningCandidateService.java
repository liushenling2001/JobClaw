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
    private static final String METADATA_RECORD_TYPE = "learningRecordType";
    private static final String RECORD_TYPE_FAILURE_EVIDENCE = "failure_evidence";
    private static final String METADATA_FAILURE_SIGNATURE = "failureSignature";
    private static final String METADATA_OCCURRENCE_COUNT = "occurrenceCount";
    private static final String METADATA_SOURCE_RUN_IDS = "sourceRunIds";
    private static final int NEGATIVE_LESSON_MIN_OCCURRENCES = 3;

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
        // A single successful run is reference evidence, not experience.
        // Positive workflow experience should be promoted only after repeated
        // similar use by the workflow memory layer, otherwise candidates become noise.
        if (!shouldCaptureSuccessfulRun(run)) {
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
        store.saveAll(candidates);
    }

    public void recordFailedRun(TaskHarnessRun run, String reason) {
        if (run == null || run.isSuccess() || run.getDoneDefinition() == null) {
            return;
        }
        if (!hasAttributableFailure(run, reason)) {
            return;
        }
        List<LearningCandidate> candidates = new ArrayList<>(store.list());
        if (hasCandidateForRun(candidates, run.getRunId(), LearningCandidateType.NEGATIVE_LESSON)) {
            return;
        }
        upsertNegativeLessonEvidence(candidates, run, reason);
        store.saveAll(candidates);
    }

    public List<LearningCandidate> listPending() {
        return store.list().stream()
                .filter(candidate -> !isInternalEvidence(candidate))
                .filter(candidate -> candidate.getStatus() == LearningCandidateStatus.PENDING)
                .toList();
    }

    public List<LearningCandidate> list(String status) {
        if (status == null || status.isBlank()) {
            return store.list().stream()
                    .filter(candidate -> !isInternalEvidence(candidate))
                    .toList();
        }
        LearningCandidateStatus parsedStatus = parseStatus(status);
        return store.list().stream()
                .filter(candidate -> !isInternalEvidence(candidate))
                .filter(candidate -> candidate.getStatus() == parsedStatus)
                .toList();
    }

    public Optional<LearningCandidate> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return store.list().stream()
                .filter(candidate -> !isInternalEvidence(candidate))
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
        return delete(id).map(candidate -> {
            candidate.setStatus(LearningCandidateStatus.REJECTED);
            candidate.setUpdatedAt(Instant.now());
            return candidate;
        });
    }

    public Optional<LearningCandidate> delete(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        List<LearningCandidate> candidates = new ArrayList<>(store.list());
        for (int i = 0; i < candidates.size(); i++) {
            LearningCandidate candidate = candidates.get(i);
            if (id.equals(candidate.getId())) {
                candidates.remove(i);
                store.saveAll(candidates);
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private void upsertNegativeLessonEvidence(List<LearningCandidate> candidates,
                                              TaskHarnessRun run,
                                              String reason) {
        String signature = failureSignature(run, reason);
        Optional<LearningCandidate> existingVisible = candidates.stream()
                .filter(candidate -> candidate.getType() == LearningCandidateType.NEGATIVE_LESSON)
                .filter(candidate -> !isInternalEvidence(candidate))
                .filter(candidate -> signature.equals(candidate.getMetadata().get(METADATA_FAILURE_SIGNATURE)))
                .findFirst();
        if (existingVisible.isPresent()) {
            LearningCandidate candidate = existingVisible.get();
            candidate.setUpdatedAt(Instant.now());
            candidate.setConfidence(Math.max(candidate.getConfidence(), 0.85));
            candidate.getMetadata().put(METADATA_OCCURRENCE_COUNT,
                    Math.max(numberValue(candidate.getMetadata().get(METADATA_OCCURRENCE_COUNT)), NEGATIVE_LESSON_MIN_OCCURRENCES));
            return;
        }

        LearningCandidate evidence = candidates.stream()
                .filter(this::isInternalEvidence)
                .filter(candidate -> signature.equals(candidate.getMetadata().get(METADATA_FAILURE_SIGNATURE)))
                .findFirst()
                .orElseGet(() -> {
                    LearningCandidate created = negativeLessonCandidate(run, reason);
                    created.setStatus(LearningCandidateStatus.REJECTED);
                    created.setTitle("失败经验证据");
                    created.setReason("同类失败证据累计中，达到阈值后才会显示为候选。");
                    created.setConfidence(0.35);
                    created.getMetadata().put(METADATA_RECORD_TYPE, RECORD_TYPE_FAILURE_EVIDENCE);
                    created.getMetadata().put(METADATA_FAILURE_SIGNATURE, signature);
                    created.getMetadata().put(METADATA_OCCURRENCE_COUNT, 0);
                    created.getMetadata().put(METADATA_SOURCE_RUN_IDS, new ArrayList<String>());
                    candidates.add(created);
                    return created;
                });

        List<String> runIds = stringList(evidence.getMetadata().get(METADATA_SOURCE_RUN_IDS));
        if (run.getRunId() != null && !runIds.contains(run.getRunId())) {
            runIds.add(run.getRunId());
        }
        int occurrenceCount = Math.max(numberValue(evidence.getMetadata().get(METADATA_OCCURRENCE_COUNT)), runIds.size());
        evidence.getMetadata().put(METADATA_SOURCE_RUN_IDS, runIds);
        evidence.getMetadata().put(METADATA_OCCURRENCE_COUNT, occurrenceCount);
        evidence.setUpdatedAt(Instant.now());

        if (occurrenceCount >= NEGATIVE_LESSON_MIN_OCCURRENCES) {
            promoteFailureEvidence(evidence, run, reason, occurrenceCount);
        }
    }

    private void promoteFailureEvidence(LearningCandidate evidence,
                                        TaskHarnessRun run,
                                        String reason,
                                        int occurrenceCount) {
        LearningCandidate promoted = negativeLessonCandidate(run, reason);
        evidence.setType(promoted.getType());
        evidence.setStatus(LearningCandidateStatus.PENDING);
        evidence.setTitle("重复失败经验候选");
        evidence.setReason("同类任务失败已累计 " + occurrenceCount + " 次，建议用户确认是否沉淀为负向经验。");
        evidence.setSessionId(promoted.getSessionId());
        evidence.setSourceRunId(promoted.getSourceRunId());
        evidence.setTaskInput(promoted.getTaskInput());
        evidence.setPlanningMode(promoted.getPlanningMode());
        evidence.setDeliveryType(promoted.getDeliveryType());
        evidence.setProposal(promoted.getProposal());
        evidence.setTags(promoted.getTags());
        evidence.setConfidence(Math.max(0.85, promoted.getConfidence()));
        evidence.getMetadata().remove(METADATA_RECORD_TYPE);
        evidence.getMetadata().putAll(promoted.getMetadata());
        evidence.getMetadata().put(METADATA_FAILURE_SIGNATURE, failureSignature(run, reason));
        evidence.getMetadata().put(METADATA_OCCURRENCE_COUNT, occurrenceCount);
        evidence.setUpdatedAt(Instant.now());
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
        candidate.setReason("任务在修复后成功完成，并包含可复用工具序列。普通成功任务不自动进入候选，避免经验泛滥。");
        candidate.setProposal(buildWorkflowProposal(run, tools));
        candidate.setTags(List.of("workflow", run.getPlanningMode().name(), run.getDoneDefinition().deliveryType().name()));
        candidate.setConfidence(run.hasTrackedSubtasks() ? 0.8 : 0.75);
        candidate.setMetadata(Map.of(
                "toolSequence", tools,
                "hasTrackedSubtasks", run.hasTrackedSubtasks(),
                "pendingSubtasks", run.getPendingSubtaskCount(),
                "repairAttempts", run.getRepairAttempts()
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
        candidate.setConfidence(run.getLastFailure() != null ? 0.8 : 0.75);
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

    private boolean isRepeatable(DeliveryType deliveryType) {
        return deliveryType != DeliveryType.ANSWER;
    }

    private boolean shouldCaptureSuccessfulRun(TaskHarnessRun run) {
        return false;
    }

    private boolean hasAttributableFailure(TaskHarnessRun run, String reason) {
        return run.getLastFailure() != null
                || run.getPendingSubtaskCount() > 0
                || run.getRepairAttempts() > 0
                || (reason != null && reason.trim().length() >= 12);
    }

    private boolean hasCandidateForRun(List<LearningCandidate> candidates,
                                       String runId,
                                       LearningCandidateType type) {
        return candidates.stream().anyMatch(candidate ->
                type == candidate.getType() && runId != null && runId.equals(candidate.getSourceRunId()));
    }

    private boolean isInternalEvidence(LearningCandidate candidate) {
        return candidate != null
                && RECORD_TYPE_FAILURE_EVIDENCE.equals(candidate.getMetadata().get(METADATA_RECORD_TYPE));
    }

    private String failureSignature(TaskHarnessRun run, String reason) {
        String planningMode = run.getPlanningMode() != null ? run.getPlanningMode().name() : "UNKNOWN";
        String deliveryType = run.getDoneDefinition() != null && run.getDoneDefinition().deliveryType() != null
                ? run.getDoneDefinition().deliveryType().name()
                : "UNKNOWN";
        String failureKind = run.getLastFailure() != null && run.getLastFailure().kind() != null
                ? run.getLastFailure().kind().name()
                : "UNKNOWN";
        String normalizedReason = normalizeSignatureText(reason != null ? reason : failureKind);
        String taskTerms = String.join("_", signatureTerms(run.getTaskInput()).stream().limit(6).toList());
        return planningMode + "|" + deliveryType + "|" + failureKind + "|" + normalizedReason + "|" + taskTerms;
    }

    private Set<String> signatureTerms(String value) {
        String text = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String token : text.split("[\\s,，。！？!?;；:：、()（）\\[\\]{}\"'`/\\\\]+")) {
            String normalized = token.trim();
            if (normalized.length() >= 2) {
                terms.add(normalized);
            }
        }
        addIfPresent(terms, text, "pdf");
        addIfPresent(terms, text, "excel");
        addIfPresent(terms, text, "word");
        addIfPresent(terms, text, "批量");
        addIfPresent(terms, text, "审查");
        addIfPresent(terms, text, "报告");
        addIfPresent(terms, text, "总结");
        return terms;
    }

    private void addIfPresent(Set<String> terms, String text, String term) {
        if (text.contains(term)) {
            terms.add(term);
        }
    }

    private String normalizeSignatureText(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", "_")
                .replaceAll("_+", "_");
        if (normalized.length() > 80) {
            return normalized.substring(0, 80);
        }
        return normalized;
    }

    private int numberValue(Object value) {
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

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(Object::toString)
                    .filter(item -> !item.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
        return new ArrayList<>();
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
