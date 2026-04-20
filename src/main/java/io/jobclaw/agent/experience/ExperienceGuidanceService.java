package io.jobclaw.agent.experience;

import io.jobclaw.agent.completion.DoneDefinition;
import io.jobclaw.agent.learning.LearningCandidate;
import io.jobclaw.agent.learning.LearningCandidateStatus;
import io.jobclaw.agent.learning.LearningCandidateStore;
import io.jobclaw.agent.learning.LearningCandidateType;
import io.jobclaw.agent.planning.TaskPlanningMode;
import io.jobclaw.agent.workflow.WorkflowMemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class ExperienceGuidanceService {

    private static final double MIN_NEGATIVE_LESSON_CONFIDENCE = 0.85;

    private final WorkflowMemoryService workflowMemoryService;
    private final LearningCandidateStore learningCandidateStore;
    private final ExperienceMemoryService experienceMemoryService;

    @Autowired
    public ExperienceGuidanceService(WorkflowMemoryService workflowMemoryService,
                                     LearningCandidateStore learningCandidateStore,
                                     ExperienceMemoryService experienceMemoryService) {
        this.workflowMemoryService = workflowMemoryService;
        this.learningCandidateStore = learningCandidateStore;
        this.experienceMemoryService = experienceMemoryService;
    }

    public ExperienceGuidanceService(WorkflowMemoryService workflowMemoryService,
                                     LearningCandidateStore learningCandidateStore) {
        this(workflowMemoryService, learningCandidateStore, null);
    }

    public String buildGuidance(String taskInput,
                                TaskPlanningMode planningMode,
                                DoneDefinition doneDefinition) {
        String acceptedGuidance = buildAcceptedExperienceGuidance(taskInput, planningMode, doneDefinition);
        if (!acceptedGuidance.isBlank()) {
            return acceptedGuidance;
        }

        String workflowGuidance = workflowMemoryService.findRelevant(taskInput, planningMode, doneDefinition)
                .map(workflowMemoryService::buildGuidance)
                .orElse("");
        if (!workflowGuidance.isBlank()) {
            return workflowGuidance;
        }

        return findRelevantNegativeLesson(taskInput, planningMode, doneDefinition)
                .map(this::buildNegativeLessonGuidance)
                .orElse("");
    }

    private String buildAcceptedExperienceGuidance(String taskInput,
                                                   TaskPlanningMode planningMode,
                                                   DoneDefinition doneDefinition) {
        if (experienceMemoryService == null) {
            return "";
        }
        Set<String> taskTerms = signatureTerms(taskInput);
        return experienceMemoryService.listActive().stream()
                .filter(memory -> planningMode == null || memory.getPlanningMode() == null
                        || memory.getPlanningMode() == planningMode)
                .filter(memory -> doneDefinition == null || memory.getDeliveryType() == null
                        || memory.getDeliveryType() == doneDefinition.deliveryType())
                .map(memory -> new ScoredMemory(memory, relevance(memory, taskTerms)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredMemory::score).reversed()
                        .thenComparing(scored -> scored.memory().getConfidence(), Comparator.reverseOrder()))
                .map(ScoredMemory::memory)
                .findFirst()
                .map(this::buildAcceptedExperienceGuidance)
                .orElse("");
    }

    private String buildAcceptedExperienceGuidance(ExperienceMemory memory) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Accepted Experience Memory]\n");
        sb.append("The user accepted this experience. Use it as high-priority guidance unless the current instruction conflicts.\n");
        sb.append("- Type: ").append(memory.getType()).append("\n");
        if (memory.getTitle() != null && !memory.getTitle().isBlank()) {
            sb.append("- Title: ").append(singleLine(memory.getTitle())).append("\n");
        }
        if (memory.getApplicability() != null && !memory.getApplicability().isBlank()) {
            sb.append("- Applies when: ").append(singleLine(memory.getApplicability())).append("\n");
        }
        if (!memory.getToolSequence().isEmpty()) {
            sb.append("- Tool sequence: ").append(String.join(" -> ", memory.getToolSequence())).append("\n");
        }
        if (!memory.getAvoidRules().isEmpty()) {
            sb.append("- Avoid: ").append(singleLine(String.join("; ", memory.getAvoidRules()))).append("\n");
        }
        if (memory.getProposal() != null && !memory.getProposal().isBlank()) {
            sb.append("- Notes: ").append(singleLine(memory.getProposal()));
        }
        return sb.toString();
    }

    private Optional<LearningCandidate> findRelevantNegativeLesson(String taskInput,
                                                                   TaskPlanningMode planningMode,
                                                                   DoneDefinition doneDefinition) {
        Set<String> taskTerms = signatureTerms(taskInput);
        if (taskTerms.isEmpty()) {
            return Optional.empty();
        }
        return learningCandidateStore.list().stream()
                .filter(candidate -> candidate.getType() == LearningCandidateType.NEGATIVE_LESSON)
                .filter(candidate -> candidate.getStatus() != LearningCandidateStatus.REJECTED)
                .filter(candidate -> candidate.getConfidence() >= MIN_NEGATIVE_LESSON_CONFIDENCE)
                .filter(candidate -> planningMode == null || candidate.getPlanningMode() == null
                        || candidate.getPlanningMode() == planningMode)
                .filter(candidate -> doneDefinition == null || candidate.getDeliveryType() == null
                        || candidate.getDeliveryType() == doneDefinition.deliveryType())
                .map(candidate -> new ScoredCandidate(candidate, relevance(candidate, taskTerms)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredCandidate::score).reversed()
                        .thenComparing(scored -> scored.candidate().getConfidence(), Comparator.reverseOrder()))
                .map(ScoredCandidate::candidate)
                .findFirst();
    }

    private String buildNegativeLessonGuidance(LearningCandidate candidate) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Relevant Negative Lesson]\n");
        sb.append("A similar task previously failed. Use this only to avoid repeating the same failed path; current user instructions still have priority.\n");
        if (candidate.getTaskInput() != null && !candidate.getTaskInput().isBlank()) {
            sb.append("- Previous task: ").append(singleLine(candidate.getTaskInput())).append("\n");
        }
        if (candidate.getReason() != null && !candidate.getReason().isBlank()) {
            sb.append("- Lesson: ").append(singleLine(candidate.getReason())).append("\n");
        }
        Object failureReason = candidate.getMetadata().get("failureReason");
        if (failureReason != null && !failureReason.toString().isBlank()) {
            sb.append("- Failure reason: ").append(singleLine(failureReason.toString())).append("\n");
        }
        Object toolSequence = candidate.getMetadata().get("toolSequence");
        if (toolSequence != null && !toolSequence.toString().isBlank()) {
            sb.append("- Prior tools: ").append(singleLine(toolSequence.toString())).append("\n");
        }
        sb.append("- Avoid: do not blindly repeat that prior path; verify worklist/progress/completion evidence before finalizing.");
        return sb.toString();
    }

    private int relevance(LearningCandidate candidate, Set<String> taskTerms) {
        Set<String> candidateTerms = signatureTerms(candidate.getTaskInput() + " "
                + candidate.getProposal() + " "
                + candidate.getReason());
        int score = 0;
        for (String term : taskTerms) {
            if (candidateTerms.contains(term)) {
                score++;
            }
        }
        return score;
    }

    private int relevance(ExperienceMemory memory, Set<String> taskTerms) {
        Set<String> memoryTerms = signatureTerms(memory.getApplicability() + " "
                + memory.getProposal() + " "
                + memory.getTitle() + " "
                + String.join(" ", memory.getAvoidRules()));
        int score = 0;
        for (String term : taskTerms) {
            if (memoryTerms.contains(term)) {
                score++;
            }
        }
        return score;
    }

    private Set<String> signatureTerms(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
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

    private String singleLine(String value) {
        return value.replace("\r", " ").replace("\n", " ").trim();
    }

    private record ScoredCandidate(LearningCandidate candidate, int score) {
    }

    private record ScoredMemory(ExperienceMemory memory, int score) {
    }
}
