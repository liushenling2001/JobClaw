package io.jobclaw.agent.experience;

import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.learning.FileLearningCandidateStore;
import io.jobclaw.agent.learning.LearningCandidate;
import io.jobclaw.agent.learning.LearningCandidateStatus;
import io.jobclaw.agent.learning.LearningCandidateType;
import io.jobclaw.agent.planning.TaskPlanningMode;
import io.jobclaw.agent.workflow.FileWorkflowMemoryStore;
import io.jobclaw.agent.workflow.WorkflowRecipe;
import io.jobclaw.config.Config;
import io.jobclaw.providers.LLMProvider;
import io.jobclaw.providers.LLMResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExperienceReviewServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteDailyExperienceReviewFromWorkflowAndLearningStores() throws Exception {
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        FileWorkflowMemoryStore workflowStore = new FileWorkflowMemoryStore(
                tempDir.resolve(".jobclaw").resolve("workflows").toString());
        FileLearningCandidateStore candidateStore = new FileLearningCandidateStore(
                tempDir.resolve(".jobclaw").resolve("learning").toString());
        FileExperienceMemoryStore experienceMemoryStore = new FileExperienceMemoryStore(
                tempDir.resolve(".jobclaw").resolve("experience").toString());
        workflowStore.saveAll(List.of(workflow("批量 PDF 审查流程")));
        candidateStore.saveAll(List.of(
                candidate("可升级为 skill 的流程候选", LearningCandidateType.SKILL_UPDATE),
                candidate("失败经验候选", LearningCandidateType.NEGATIVE_LESSON)
        ));
        new ExperienceMemoryService(experienceMemoryStore)
                .applyAcceptedCandidate(candidate("用户确认的流程经验", LearningCandidateType.SKILL_UPDATE));

        ExperienceReviewService service = new ExperienceReviewService(config, candidateStore, workflowStore, experienceMemoryStore, null);

        ExperienceReviewResult result = service.reviewNow();

        assertEquals(1, result.workflowCount());
        assertEquals(2, result.pendingCandidateCount());
        assertTrue(Files.exists(result.reportPath()));
        assertTrue(Files.exists(result.latestPath()));
        String report = Files.readString(result.latestPath());
        assertTrue(report.contains("批量 PDF 审查流程"));
        assertTrue(report.contains("Accepted Experience Memory"));
        assertTrue(report.contains("用户确认的流程经验"));
        assertTrue(report.contains("可升级为 skill 的流程候选"));
        assertTrue(report.contains("Negative Lessons"));
        assertTrue(report.contains("失败经验候选"));
        assertTrue(report.contains("This review is evidence only"));
    }

    @Test
    void shouldAppendLlmRefinementWhenEnabled() throws Exception {
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        config.getExperience().setLlmReviewEnabled(true);
        FileWorkflowMemoryStore workflowStore = new FileWorkflowMemoryStore(
                tempDir.resolve(".jobclaw").resolve("workflows").toString());
        FileLearningCandidateStore candidateStore = new FileLearningCandidateStore(
                tempDir.resolve(".jobclaw").resolve("learning").toString());
        workflowStore.saveAll(List.of(workflow("批量 PDF 审查流程")));
        candidateStore.saveAll(List.of(candidate("失败经验候选", LearningCandidateType.NEGATIVE_LESSON)));
        LLMProvider llmProvider = mock(LLMProvider.class);
        when(llmProvider.chat(anyList(), anyList(), isNull(), any(LLMProvider.LLMOptions.class)))
                .thenReturn(new LLMResponse("- 需要避免提前结束。"));

        ExperienceReviewService service = new ExperienceReviewService(config, candidateStore, workflowStore, llmProvider);

        ExperienceReviewResult result = service.reviewNow();

        String report = Files.readString(result.latestPath());
        assertTrue(report.contains("LLM Refined Insights"));
        assertTrue(report.contains("需要避免提前结束"));
        verify(llmProvider).chat(anyList(), anyList(), isNull(), any(LLMProvider.LLMOptions.class));
    }

    @Test
    void shouldNotCallLlmWhenDisabled() throws Exception {
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        config.getExperience().setLlmReviewEnabled(false);
        FileWorkflowMemoryStore workflowStore = new FileWorkflowMemoryStore(
                tempDir.resolve(".jobclaw").resolve("workflows").toString());
        FileLearningCandidateStore candidateStore = new FileLearningCandidateStore(
                tempDir.resolve(".jobclaw").resolve("learning").toString());
        candidateStore.saveAll(List.of(candidate("失败经验候选", LearningCandidateType.NEGATIVE_LESSON)));
        LLMProvider llmProvider = mock(LLMProvider.class);

        ExperienceReviewService service = new ExperienceReviewService(config, candidateStore, workflowStore, llmProvider);

        service.reviewNow();

        verify(llmProvider, never()).chat(anyList(), anyList(), isNull(), any(LLMProvider.LLMOptions.class));
    }

    private WorkflowRecipe workflow(String name) {
        WorkflowRecipe recipe = new WorkflowRecipe();
        recipe.setId("workflow-1");
        recipe.setName(name);
        recipe.setTaskSignature("batch_pdf_review");
        recipe.setApplicability("目录下多个 PDF 文件逐个审查");
        recipe.setPlanningMode(TaskPlanningMode.WORKLIST);
        recipe.setDeliveryType(DeliveryType.BATCH_RESULTS);
        recipe.setToolSequence(List.of("list_dir", "subtasks", "spawn", "read_pdf"));
        recipe.setSuccessCount(3);
        recipe.setConfidence(0.8);
        recipe.setCreatedAt(Instant.now());
        recipe.setLastUsedAt(Instant.now());
        return recipe;
    }

    private LearningCandidate candidate(String title, LearningCandidateType type) {
        LearningCandidate candidate = new LearningCandidate();
        candidate.setId("candidate-1");
        candidate.setType(type);
        candidate.setStatus(LearningCandidateStatus.PENDING);
        candidate.setTitle(title);
        candidate.setReason("重复成功后可由用户确认固化");
        candidate.setPlanningMode(TaskPlanningMode.WORKLIST);
        candidate.setDeliveryType(DeliveryType.BATCH_RESULTS);
        candidate.setConfidence(0.7);
        candidate.setCreatedAt(Instant.now());
        candidate.setUpdatedAt(Instant.now());
        return candidate;
    }
}
