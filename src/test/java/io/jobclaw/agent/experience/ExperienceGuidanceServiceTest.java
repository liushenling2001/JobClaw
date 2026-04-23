package io.jobclaw.agent.experience;

import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.completion.DoneDefinition;
import io.jobclaw.agent.learning.FileLearningCandidateStore;
import io.jobclaw.agent.learning.LearningCandidate;
import io.jobclaw.agent.learning.LearningCandidateStatus;
import io.jobclaw.agent.learning.LearningCandidateType;
import io.jobclaw.agent.planning.TaskPlanningMode;
import io.jobclaw.agent.workflow.FileWorkflowMemoryStore;
import io.jobclaw.agent.workflow.WorkflowMemoryService;
import io.jobclaw.agent.workflow.WorkflowRecipe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceGuidanceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPreferAcceptedExperienceAndInjectOnlyOneGuidanceBlock() {
        FileLearningCandidateStore candidateStore = new FileLearningCandidateStore(
                tempDir.resolve("learning").toString());
        FileWorkflowMemoryStore workflowStore = new FileWorkflowMemoryStore(
                tempDir.resolve("workflows").toString());
        FileExperienceMemoryStore experienceMemoryStore = new FileExperienceMemoryStore(
                tempDir.resolve("experience").toString());
        candidateStore.saveAll(List.of(negativeLesson(LearningCandidateStatus.PENDING)));
        workflowStore.saveAll(List.of(workflow()));
        new ExperienceMemoryService(experienceMemoryStore).applyAcceptedCandidate(acceptedExperienceCandidate());
        ExperienceGuidanceService service = new ExperienceGuidanceService(
                new WorkflowMemoryService(workflowStore),
                candidateStore,
                new ExperienceMemoryService(experienceMemoryStore),
                sameTaskJudger()
        );

        String guidance = service.buildGuidance(
                "批量审查目录中的 PDF 文件",
                TaskPlanningMode.WORKLIST,
                doneDefinition()
        );

        assertTrue(guidance.contains("[Accepted Experience Memory]"));
        assertFalse(guidance.contains("[Relevant Negative Lesson]"));
        assertFalse(guidance.contains("[Relevant Prior Workflow Reference]"));
    }

    @Test
    void shouldUseWorkflowReferenceWhenNoAcceptedExperienceExists() {
        FileLearningCandidateStore candidateStore = new FileLearningCandidateStore(
                tempDir.resolve("learning-workflow").toString());
        FileWorkflowMemoryStore workflowStore = new FileWorkflowMemoryStore(
                tempDir.resolve("workflows-workflow").toString());
        workflowStore.saveAll(List.of(workflow()));
        ExperienceGuidanceService service = new ExperienceGuidanceService(
                new WorkflowMemoryService(workflowStore),
                candidateStore,
                null,
                sameTaskJudger()
        );

        String guidance = service.buildGuidance(
                "批量审查目录中的 PDF 文件",
                TaskPlanningMode.WORKLIST,
                doneDefinition()
        );

        assertTrue(guidance.contains("[Relevant Prior Workflow Reference]"));
        assertTrue(guidance.contains("成功批量 PDF 审查流程"));
    }

    @Test
    void shouldUseOnlyHighConfidenceNegativeLessonWhenNoBetterExperienceExists() {
        FileLearningCandidateStore candidateStore = new FileLearningCandidateStore(
                tempDir.resolve("learning-negative").toString());
        FileWorkflowMemoryStore workflowStore = new FileWorkflowMemoryStore(
                tempDir.resolve("workflows-negative").toString());
        LearningCandidate candidate = negativeLesson(LearningCandidateStatus.PENDING);
        candidate.setConfidence(0.9);
        candidateStore.saveAll(List.of(candidate));
        ExperienceGuidanceService service = new ExperienceGuidanceService(
                new WorkflowMemoryService(workflowStore),
                candidateStore,
                null,
                sameTaskJudger()
        );

        String guidance = service.buildGuidance(
                "批量审查目录中的 PDF 文件",
                TaskPlanningMode.WORKLIST,
                doneDefinition()
        );

        assertTrue(guidance.contains("[Relevant Negative Lesson]"));
        assertTrue(guidance.contains("pending subtasks remained"));
    }

    @Test
    void shouldIgnoreRejectedNegativeLessons() {
        FileLearningCandidateStore candidateStore = new FileLearningCandidateStore(
                tempDir.resolve("learning").toString());
        FileWorkflowMemoryStore workflowStore = new FileWorkflowMemoryStore(
                tempDir.resolve("workflows").toString());
        candidateStore.saveAll(List.of(negativeLesson(LearningCandidateStatus.REJECTED)));
        ExperienceGuidanceService service = new ExperienceGuidanceService(
                new WorkflowMemoryService(workflowStore),
                candidateStore,
                null,
                sameTaskJudger()
        );

        String guidance = service.buildGuidance(
                "批量审查目录中的 PDF 文件",
                TaskPlanningMode.WORKLIST,
                doneDefinition()
        );

        assertFalse(guidance.contains("[Relevant Negative Lesson]"));
    }

    @Test
    void shouldNotInjectWorkflowWhenSemanticJudgerRejectsMatch() {
        FileLearningCandidateStore candidateStore = new FileLearningCandidateStore(
                tempDir.resolve("learning-semantic").toString());
        FileWorkflowMemoryStore workflowStore = new FileWorkflowMemoryStore(
                tempDir.resolve("workflows-semantic").toString());
        workflowStore.saveAll(List.of(workflow()));
        ExperienceGuidanceService service = new ExperienceGuidanceService(
                new WorkflowMemoryService(workflowStore),
                candidateStore,
                null,
                (current, previous, planningMode, deliveryType) -> false
        );

        String guidance = service.buildGuidance(
                "总结目录中的 PDF 文献并生成综述 Word",
                TaskPlanningMode.WORKLIST,
                doneDefinition()
        );

        assertFalse(guidance.contains("[Relevant Prior Workflow Reference]"));
    }

    @Test
    void shouldSkipExperienceGuidanceWhenSemanticJudgerFails() {
        FileLearningCandidateStore candidateStore = new FileLearningCandidateStore(
                tempDir.resolve("learning-semantic-failure").toString());
        FileWorkflowMemoryStore workflowStore = new FileWorkflowMemoryStore(
                tempDir.resolve("workflows-semantic-failure").toString());
        workflowStore.saveAll(List.of(workflow()));
        ExperienceGuidanceService service = new ExperienceGuidanceService(
                new WorkflowMemoryService(workflowStore),
                candidateStore,
                null,
                (current, previous, planningMode, deliveryType) -> {
                    throw new RuntimeException("classifier unavailable");
                }
        );

        String guidance = service.buildGuidance(
                "批量审查目录中的 PDF 文件",
                TaskPlanningMode.WORKLIST,
                doneDefinition()
        );

        assertFalse(guidance.contains("[Relevant Prior Workflow Reference]"));
    }

    @Test
    void shouldCallSemanticJudgerOnlyOnceForBestLocalCandidate() {
        FileLearningCandidateStore candidateStore = new FileLearningCandidateStore(
                tempDir.resolve("learning-one-check").toString());
        FileWorkflowMemoryStore workflowStore = new FileWorkflowMemoryStore(
                tempDir.resolve("workflows-one-check").toString());
        FileExperienceMemoryStore experienceMemoryStore = new FileExperienceMemoryStore(
                tempDir.resolve("experience-one-check").toString());
        candidateStore.saveAll(List.of(negativeLesson(LearningCandidateStatus.PENDING)));
        workflowStore.saveAll(List.of(workflow()));
        new ExperienceMemoryService(experienceMemoryStore).applyAcceptedCandidate(acceptedExperienceCandidate());
        AtomicInteger checks = new AtomicInteger();
        ExperienceGuidanceService service = new ExperienceGuidanceService(
                new WorkflowMemoryService(workflowStore),
                candidateStore,
                new ExperienceMemoryService(experienceMemoryStore),
                (current, previous, planningMode, deliveryType) -> {
                    checks.incrementAndGet();
                    return false;
                }
        );

        String guidance = service.buildGuidance(
                "批量审查目录中的 PDF 文件",
                TaskPlanningMode.WORKLIST,
                doneDefinition()
        );

        assertFalse(guidance.contains("[Accepted Experience Memory]"));
        assertEquals(1, checks.get());
    }

    @Test
    void shouldNotCallSemanticJudgerForWeakOrConflictingCandidate() {
        FileLearningCandidateStore candidateStore = new FileLearningCandidateStore(
                tempDir.resolve("learning-weak-gate").toString());
        FileWorkflowMemoryStore workflowStore = new FileWorkflowMemoryStore(
                tempDir.resolve("workflows-weak-gate").toString());
        FileExperienceMemoryStore experienceMemoryStore = new FileExperienceMemoryStore(
                tempDir.resolve("experience-weak-gate").toString());
        new ExperienceMemoryService(experienceMemoryStore).applyAcceptedCandidate(acceptedExperienceCandidate());
        AtomicInteger checks = new AtomicInteger();
        ExperienceGuidanceService service = new ExperienceGuidanceService(
                new WorkflowMemoryService(workflowStore),
                candidateStore,
                new ExperienceMemoryService(experienceMemoryStore),
                (current, previous, planningMode, deliveryType) -> {
                    checks.incrementAndGet();
                    return true;
                }
        );

        String guidance = service.buildGuidance(
                "总结目录中的 PDF 文献并生成综述 Word",
                TaskPlanningMode.WORKLIST,
                doneDefinition()
        );

        assertFalse(guidance.contains("[Accepted Experience Memory]"));
        assertEquals(0, checks.get());
    }

    private DoneDefinition doneDefinition() {
        return new DoneDefinition(
                TaskPlanningMode.WORKLIST,
                DeliveryType.BATCH_RESULTS,
                List.of(),
                List.of(),
                true,
                true,
                false,
                List.of("worklist")
        );
    }

    private LearningCandidate negativeLesson(LearningCandidateStatus status) {
        LearningCandidate candidate = new LearningCandidate();
        candidate.setId("negative-1");
        candidate.setType(LearningCandidateType.NEGATIVE_LESSON);
        candidate.setStatus(status);
        candidate.setTitle("失败经验候选");
        candidate.setReason("类似 PDF 批量审查曾在子任务未完成时提前结束");
        candidate.setTaskInput("批量审查 PDF 文件");
        candidate.setPlanningMode(TaskPlanningMode.WORKLIST);
        candidate.setDeliveryType(DeliveryType.BATCH_RESULTS);
        candidate.setConfidence(0.7);
        candidate.setProposal("Task failed and should not be blindly repeated.");
        candidate.setMetadata(Map.of(
                "failureReason", "pending subtasks remained",
                "toolSequence", List.of("list_dir", "subtasks", "spawn")
        ));
        candidate.setCreatedAt(Instant.now());
        candidate.setUpdatedAt(Instant.now());
        return candidate;
    }

    private WorkflowRecipe workflow() {
        WorkflowRecipe recipe = new WorkflowRecipe();
        recipe.setId("workflow-1");
        recipe.setName("成功批量 PDF 审查流程");
        recipe.setApplicability("批量审查 PDF 文件");
        recipe.setTaskSignature("批量_pdf_审查");
        recipe.setPlanningMode(TaskPlanningMode.WORKLIST);
        recipe.setDeliveryType(DeliveryType.BATCH_RESULTS);
        recipe.setToolSequence(List.of("list_dir", "subtasks", "spawn", "read_pdf"));
        recipe.setSuccessCount(2);
        recipe.setConfidence(0.8);
        recipe.setCreatedAt(Instant.now());
        recipe.setLastUsedAt(Instant.now());
        return recipe;
    }

    private TaskSimilarityJudger sameTaskJudger() {
        return (current, previous, planningMode, deliveryType) -> true;
    }

    private LearningCandidate acceptedExperienceCandidate() {
        LearningCandidate candidate = negativeLesson(LearningCandidateStatus.ACCEPTED);
        candidate.setId("accepted-1");
        candidate.setType(LearningCandidateType.SKILL_UPDATE);
        candidate.setTitle("用户确认的 PDF 批量审查经验");
        candidate.setReason("该流程可复用");
        candidate.setProposal("Use one-file-per-subtask workflow.");
        return candidate;
    }
}
