package io.jobclaw.agent.workflow;

import io.jobclaw.agent.TaskHarnessRun;
import io.jobclaw.agent.TaskHarnessStep;
import io.jobclaw.agent.TaskHarnessSubtaskStatus;
import io.jobclaw.agent.completion.DoneDefinition;
import io.jobclaw.agent.experience.ExperienceMemoryService;
import io.jobclaw.agent.planning.TaskPlanningMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class WorkflowMemoryService {

    private static final double MIN_GUIDANCE_CONFIDENCE = 0.45;
    private static final int AUTO_PROMOTE_SUCCESS_THRESHOLD = 3;

    private final WorkflowMemoryStore store;
    private final ExperienceMemoryService experienceMemoryService;

    @Autowired
    public WorkflowMemoryService(WorkflowMemoryStore store,
                                 ExperienceMemoryService experienceMemoryService) {
        this.store = store;
        this.experienceMemoryService = experienceMemoryService;
    }

    public WorkflowMemoryService(WorkflowMemoryStore store) {
        this.store = store;
        this.experienceMemoryService = null;
    }

    public Optional<WorkflowRecipe> findRelevant(String taskInput,
                                                 TaskPlanningMode planningMode,
                                                 DoneDefinition doneDefinition) {
        Set<String> taskTerms = signatureTerms(taskInput);
        if (taskTerms.isEmpty()) {
            return Optional.empty();
        }
        return store.list().stream()
                .filter(recipe -> recipe.getPlanningMode() == planningMode)
                .filter(recipe -> doneDefinition == null || recipe.getDeliveryType() == doneDefinition.deliveryType())
                .map(recipe -> new ScoredRecipe(recipe, relevance(recipe, taskTerms)))
                .filter(scored -> scored.score() > 0)
                .filter(scored -> scored.recipe().getConfidence() >= MIN_GUIDANCE_CONFIDENCE)
                .sorted(Comparator.comparingInt(ScoredRecipe::score).reversed()
                        .thenComparing(scored -> scored.recipe().getConfidence(), Comparator.reverseOrder()))
                .map(ScoredRecipe::recipe)
                .findFirst();
    }

    public String buildGuidance(WorkflowRecipe recipe) {
        if (recipe == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[Relevant Prior Workflow Reference]\n");
        sb.append("A similar task previously succeeded with this workflow. Treat it as reference evidence, not a hard rule, unless it has been promoted to accepted experience.\n");
        sb.append("- Name: ").append(recipe.getName()).append("\n");
        sb.append("- Prior successes: ").append(recipe.getSuccessCount()).append("\n");
        if (recipe.getApplicability() != null && !recipe.getApplicability().isBlank()) {
            sb.append("- Applies when: ").append(recipe.getApplicability()).append("\n");
        }
        if (!recipe.getToolSequence().isEmpty()) {
            sb.append("- Tool sequence: ").append(String.join(" -> ", recipe.getToolSequence())).append("\n");
        }
        if (recipe.getSubtaskPattern() != null && !recipe.getSubtaskPattern().isBlank()) {
            sb.append("- Subtask pattern: ").append(recipe.getSubtaskPattern()).append("\n");
        }
        return sb.toString();
    }

    public void recordSuccess(TaskHarnessRun run) {
        if (run == null || !run.isSuccess() || run.getDoneDefinition() == null) {
            return;
        }
        List<String> toolSequence = toolSequence(run);
        if (toolSequence.isEmpty()) {
            return;
        }

        String signature = taskSignature(run.getTaskInput());
        List<WorkflowRecipe> recipes = new ArrayList<>(store.list());
        Optional<WorkflowRecipe> existing = recipes.stream()
                .filter(recipe -> signature.equals(recipe.getTaskSignature()))
                .filter(recipe -> recipe.getPlanningMode() == run.getPlanningMode())
                .filter(recipe -> recipe.getDeliveryType() == run.getDoneDefinition().deliveryType())
                .findFirst();

        WorkflowRecipe recipe = existing.orElseGet(() -> newRecipe(run, signature));
        recipe.setLastUsedAt(Instant.now());
        recipe.setSuccessCount(recipe.getSuccessCount() + 1);
        recipe.setConfidence(Math.min(0.95, Math.max(0.35, recipe.getConfidence()) + 0.15));
        recipe.setToolSequence(toolSequence);
        recipe.setRequiredTools(List.copyOf(new LinkedHashSet<>(toolSequence)));
        recipe.setSubtaskPattern(subtaskPattern(run));
        if (!recipe.getSourceRunIds().contains(run.getRunId())) {
            List<String> runIds = new ArrayList<>(recipe.getSourceRunIds());
            runIds.add(run.getRunId());
            recipe.setSourceRunIds(runIds);
        }

        if (existing.isEmpty()) {
            recipes.add(recipe);
        }
        store.saveAll(recipes);
        autoPromoteIfStable(recipe);
    }

    private void autoPromoteIfStable(WorkflowRecipe recipe) {
        if (experienceMemoryService == null || recipe == null) {
            return;
        }
        if (recipe.getSuccessCount() < AUTO_PROMOTE_SUCCESS_THRESHOLD) {
            return;
        }
        if (recipe.getConfidence() < 0.75) {
            return;
        }
        experienceMemoryService.applyAutoPromotedWorkflow(recipe);
    }

    private WorkflowRecipe newRecipe(TaskHarnessRun run, String signature) {
        WorkflowRecipe recipe = new WorkflowRecipe();
        recipe.setId(UUID.randomUUID().toString());
        recipe.setName(nameFor(run));
        recipe.setTaskSignature(signature);
        recipe.setApplicability(run.getTaskInput());
        recipe.setPlanningMode(run.getPlanningMode());
        recipe.setDeliveryType(run.getDoneDefinition().deliveryType());
        recipe.setSuccessCount(0);
        recipe.setConfidence(0.35);
        recipe.setCreatedAt(Instant.now());
        recipe.setLastUsedAt(Instant.now());
        return recipe;
    }

    private String nameFor(TaskHarnessRun run) {
        if (run.getPlanningMode() == TaskPlanningMode.WORKLIST) {
            return "成功批处理流程";
        }
        return switch (run.getDoneDefinition().deliveryType()) {
            case FILE_ARTIFACT -> "成功文件产物流程";
            case DOCUMENT_SUMMARY -> "成功文档分析流程";
            case PATCH -> "成功文件修改流程";
            case BATCH_RESULTS -> "成功批处理流程";
            case ANSWER -> "成功问答流程";
        };
    }

    private List<String> toolSequence(TaskHarnessRun run) {
        LinkedHashSet<String> sequence = new LinkedHashSet<>();
        for (TaskHarnessStep step : run.getSteps()) {
            Object eventType = step.metadata().get("eventType");
            if (!"TOOL_START".equals(eventType)) {
                continue;
            }
            String toolName = stringValue(step.metadata().get("toolName"));
            if (!toolName.isBlank()) {
                sequence.add(toolName);
            }
        }
        return List.copyOf(sequence);
    }

    private String subtaskPattern(TaskHarnessRun run) {
        if (!run.hasTrackedSubtasks()) {
            return "";
        }
        long completed = run.getSubtasks().stream()
                .filter(subtask -> subtask.status() == TaskHarnessSubtaskStatus.COMPLETED).count();
        long failed = run.getSubtasks().stream()
                .filter(subtask -> subtask.status() == TaskHarnessSubtaskStatus.FAILED).count();
        return "tracked_subtasks completed=" + completed + " failed=" + failed;
    }

    private int relevance(WorkflowRecipe recipe, Set<String> taskTerms) {
        Set<String> recipeTerms = signatureTerms(recipe.getApplicability() + " " + recipe.getTaskSignature());
        int score = 0;
        for (String term : taskTerms) {
            if (recipeTerms.contains(term)) {
                score++;
            }
        }
        return score;
    }

    private String taskSignature(String taskInput) {
        return String.join("_", signatureTerms(taskInput).stream().limit(8).toList());
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

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private record ScoredRecipe(WorkflowRecipe recipe, int score) {
    }
}
