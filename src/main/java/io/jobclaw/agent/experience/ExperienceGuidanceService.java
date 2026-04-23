package io.jobclaw.agent.experience;

import io.jobclaw.agent.completion.DoneDefinition;
import io.jobclaw.agent.learning.LearningCandidate;
import io.jobclaw.agent.learning.LearningCandidateStatus;
import io.jobclaw.agent.learning.LearningCandidateStore;
import io.jobclaw.agent.learning.LearningCandidateType;
import io.jobclaw.agent.planning.TaskPlanningMode;
import io.jobclaw.agent.workflow.WorkflowMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class ExperienceGuidanceService {

    private static final Logger logger = LoggerFactory.getLogger(ExperienceGuidanceService.class);
    private static final double MIN_NEGATIVE_LESSON_CONFIDENCE = 0.85;
    private static final Set<String> FORMAT_AND_CONTAINER_TERMS = Set.of(
            "pdf", "word", "excel", "doc", "docx", "xls", "xlsx", "文件", "目录", "文件夹", "文档"
    );
    private static final Set<String> REVIEW_INTENTS = Set.of("审查", "检查", "审核", "校验", "验证", "是否符合", "格式要求");
    private static final Set<String> SUMMARY_INTENTS = Set.of("总结", "综述", "概括", "提炼", "归纳", "分析");
    private static final Set<String> WRITE_INTENTS = Set.of("撰写", "写", "生成", "创建", "完善", "修改", "报告", "word");

    private final WorkflowMemoryService workflowMemoryService;
    private final LearningCandidateStore learningCandidateStore;
    private final ExperienceMemoryService experienceMemoryService;
    private final ObjectProvider<TaskSimilarityJudger> taskSimilarityJudgerProvider;
    private final TaskSimilarityJudger testTaskSimilarityJudger;

    @Autowired
    public ExperienceGuidanceService(WorkflowMemoryService workflowMemoryService,
                                     LearningCandidateStore learningCandidateStore,
                                     ExperienceMemoryService experienceMemoryService,
                                     ObjectProvider<TaskSimilarityJudger> taskSimilarityJudgerProvider) {
        this.workflowMemoryService = workflowMemoryService;
        this.learningCandidateStore = learningCandidateStore;
        this.experienceMemoryService = experienceMemoryService;
        this.taskSimilarityJudgerProvider = taskSimilarityJudgerProvider;
        this.testTaskSimilarityJudger = null;
    }

    public ExperienceGuidanceService(WorkflowMemoryService workflowMemoryService,
                                     LearningCandidateStore learningCandidateStore,
                                     ExperienceMemoryService experienceMemoryService) {
        this(workflowMemoryService, learningCandidateStore, experienceMemoryService, (TaskSimilarityJudger) null);
    }

    ExperienceGuidanceService(WorkflowMemoryService workflowMemoryService,
                              LearningCandidateStore learningCandidateStore,
                              ExperienceMemoryService experienceMemoryService,
                              TaskSimilarityJudger taskSimilarityJudger) {
        this.workflowMemoryService = workflowMemoryService;
        this.learningCandidateStore = learningCandidateStore;
        this.experienceMemoryService = experienceMemoryService;
        this.taskSimilarityJudgerProvider = null;
        this.testTaskSimilarityJudger = taskSimilarityJudger;
    }

    public ExperienceGuidanceService(WorkflowMemoryService workflowMemoryService,
                                     LearningCandidateStore learningCandidateStore) {
        this(workflowMemoryService, learningCandidateStore, null);
    }

    public String buildGuidance(String taskInput,
                                TaskPlanningMode planningMode,
                                DoneDefinition doneDefinition) {
        return findBestLocalCandidate(taskInput, planningMode, doneDefinition)
                .filter(candidate -> isSemanticallySameTask(
                        taskInput,
                        candidate.applicability(),
                        planningMode,
                        doneDefinition
                ))
                .map(GuidanceCandidate::guidance)
                .orElse("");
    }

    private Optional<GuidanceCandidate> findBestLocalCandidate(String taskInput,
                                                               TaskPlanningMode planningMode,
                                                               DoneDefinition doneDefinition) {
        Optional<GuidanceCandidate> accepted = findAcceptedExperienceGuidance(taskInput, planningMode, doneDefinition);
        if (accepted.isPresent()) {
            return accepted;
        }
        Optional<GuidanceCandidate> workflow = workflowMemoryService.findRelevant(taskInput, planningMode, doneDefinition)
                .map(recipe -> new GuidanceCandidate(
                        recipe.getApplicability(),
                        workflowMemoryService.buildGuidance(recipe)
                ));
        if (workflow.isPresent()) {
            return workflow;
        }
        return findRelevantNegativeLesson(taskInput, planningMode, doneDefinition)
                .map(candidate -> new GuidanceCandidate(
                        candidate.getTaskInput(),
                        buildNegativeLessonGuidance(candidate)
                ));
    }

    private Optional<GuidanceCandidate> findAcceptedExperienceGuidance(String taskInput,
                                                                       TaskPlanningMode planningMode,
                                                                       DoneDefinition doneDefinition) {
        if (experienceMemoryService == null) {
            return Optional.empty();
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
                .map(memory -> new GuidanceCandidate(
                        memory.getApplicability(),
                        buildAcceptedExperienceGuidance(memory)
                ));
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

    private boolean isSemanticallySameTask(String currentTask,
                                           String previousTask,
                                           TaskPlanningMode planningMode,
                                           DoneDefinition doneDefinition) {
        TaskSimilarityJudger judger = taskSimilarityJudger();
        if (judger == null) {
            return false;
        }
        if (!shouldAskSemanticJudger(currentTask, previousTask)) {
            logger.debug("Task similarity gate rejected weak candidate; skipping LLM similarity check");
            return false;
        }
        try {
            return judger.isSameTask(
                    currentTask,
                    previousTask,
                    planningMode,
                    doneDefinition != null ? doneDefinition.deliveryType() : null
            );
        } catch (Throwable e) {
            logger.warn("Task similarity check failed; skipping experience guidance: {}", e.getMessage());
            return false;
        }
    }

    private TaskSimilarityJudger taskSimilarityJudger() {
        if (testTaskSimilarityJudger != null) {
            return testTaskSimilarityJudger;
        }
        return taskSimilarityJudgerProvider != null ? taskSimilarityJudgerProvider.getIfAvailable() : null;
    }

    private boolean shouldAskSemanticJudger(String currentTask, String previousTask) {
        Set<String> currentTerms = signatureTerms(currentTask);
        Set<String> previousTerms = signatureTerms(previousTask);
        if (currentTerms.isEmpty() || previousTerms.isEmpty()) {
            return false;
        }
        Set<String> currentIntents = intentFamilies(currentTask, currentTerms);
        Set<String> previousIntents = intentFamilies(previousTask, previousTerms);
        if (currentIntents.isEmpty() || previousIntents.isEmpty()) {
            return false;
        }
        if (!hasOverlap(currentIntents, previousIntents)) {
            return false;
        }

        Set<String> sharedTerms = new LinkedHashSet<>(currentTerms);
        sharedTerms.retainAll(previousTerms);
        sharedTerms.removeAll(FORMAT_AND_CONTAINER_TERMS);

        int currentSemanticIntentCount = currentIntents.contains("batch") ? currentIntents.size() - 1 : currentIntents.size();
        int previousSemanticIntentCount = previousIntents.contains("batch") ? previousIntents.size() - 1 : previousIntents.size();
        int requiredSharedTerms = currentSemanticIntentCount > 1 || previousSemanticIntentCount > 1 ? 3 : 2;
        return sharedTerms.size() >= requiredSharedTerms;
    }

    private Set<String> intentFamilies(String text, Set<String> terms) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> intents = new LinkedHashSet<>();
        if (containsAny(value, terms, REVIEW_INTENTS)) {
            intents.add("review");
        }
        if (containsAny(value, terms, SUMMARY_INTENTS)) {
            intents.add("summary");
        }
        if (containsAny(value, terms, WRITE_INTENTS)) {
            intents.add("write");
        }
        if (terms.contains("批量")) {
            intents.add("batch");
        }
        return intents;
    }

    private boolean containsAny(String text, Set<String> terms, Set<String> markers) {
        for (String marker : markers) {
            if (terms.contains(marker) || text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasOverlap(Set<String> left, Set<String> right) {
        for (String item : left) {
            if (right.contains(item)) {
                return true;
            }
        }
        return false;
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
        addIfPresent(terms, text, "检查");
        addIfPresent(terms, text, "审核");
        addIfPresent(terms, text, "格式");
        addIfPresent(terms, text, "参考文献");
        addIfPresent(terms, text, "作者");
        addIfPresent(terms, text, "报告");
        addIfPresent(terms, text, "总结");
        addIfPresent(terms, text, "综述");
        addIfPresent(terms, text, "分析");
        addIfPresent(terms, text, "撰写");
        addIfPresent(terms, text, "生成");
        addIfPresent(terms, text, "创建");
        addIfPresent(terms, text, "完善");
        addIfPresent(terms, text, "修改");
        addIfPresent(terms, text, "招生");
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

    private record GuidanceCandidate(String applicability, String guidance) {
    }
}
