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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceGuidanceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldBuildGuidanceFromNegativeLessonAndSuccessfulWorkflow() {
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
                new ExperienceMemoryService(experienceMemoryStore)
        );

        String guidance = service.buildGuidance(
                "批量审查目录中的 PDF 文件",
                TaskPlanningMode.WORKLIST,
                doneDefinition()
        );

        assertTrue(guidance.contains("[Accepted Experience Memory]"));
        assertTrue(guidance.contains("[Relevant Negative Lesson]"));
        assertTrue(guidance.contains("pending subtasks remained"));
        assertTrue(guidance.contains("[Relevant Successful Workflow]"));
        assertTrue(guidance.contains("成功批量 PDF 审查流程"));
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
                candidateStore
        );

        String guidance = service.buildGuidance(
                "批量审查目录中的 PDF 文件",
                TaskPlanningMode.WORKLIST,
                doneDefinition()
        );

        assertFalse(guidance.contains("[Relevant Negative Lesson]"));
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
