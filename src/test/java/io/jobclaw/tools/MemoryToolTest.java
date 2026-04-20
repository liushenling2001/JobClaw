package io.jobclaw.tools;

import io.jobclaw.agent.learning.FileLearningCandidateStore;
import io.jobclaw.agent.learning.LearningCandidate;
import io.jobclaw.agent.learning.LearningCandidateStatus;
import io.jobclaw.agent.learning.LearningCandidateType;
import io.jobclaw.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryToolTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRememberSearchAndForgetDurableMemory() throws Exception {
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());

        MemoryTool tool = new MemoryTool(config);
        String remembered = tool.execute(
                "remember",
                "以后分析招生 PDF 时先看第一页作者和最后一页参考文献。",
                null,
                null,
                "preference,pdf",
                "user",
                0.9
        );

        assertTrue(remembered.contains("Remembered durable memory"));
        Path memoriesFile = tempDir.resolve("memory").resolve("MEMORIES.json");
        assertTrue(Files.exists(memoriesFile));

        String search = tool.execute("search", null, "招生 PDF", null, null, null, null);
        assertTrue(search.contains("招生 PDF"));

        String id = Files.readString(memoriesFile).split("\"id\"\\s*:\\s*\"")[1].split("\"")[0];
        String forgot = tool.execute("forget", null, null, id, null, null, null);
        assertTrue(forgot.contains("Forgot memory"));
    }

    @Test
    void shouldCreatePendingWorkflowLearningCandidateForExplicitExperienceMemory() {
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        FileLearningCandidateStore candidateStore = new FileLearningCandidateStore(
                tempDir.resolve(".jobclaw").resolve("learning").toString()
        );
        MemoryTool tool = new MemoryTool(config, candidateStore);

        String result = tool.execute(
                "remember",
                "固化为经验：批量审查 PDF 多次验证有效时，先枚举完整文件清单，再登记 subtasks worklist，最后逐个 spawn 子任务处理。",
                null,
                null,
                "workflow,pdf",
                "project",
                0.85
        );

        assertTrue(result.contains("Learning candidate created"));
        List<LearningCandidate> candidates = candidateStore.list();
        assertEquals(1, candidates.size());
        LearningCandidate candidate = candidates.get(0);
        assertEquals(LearningCandidateType.WORKFLOW, candidate.getType());
        assertEquals(LearningCandidateStatus.PENDING, candidate.getStatus());
        assertNull(candidate.getDeliveryType());
        assertEquals("memory_tool_explicit", candidate.getMetadata().get("source"));
        assertTrue(candidate.getTags().contains("explicit_memory"));
        assertTrue(candidate.getTags().contains("workflow"));
    }

    @Test
    void shouldCreatePendingNegativeLessonCandidateForExplicitFailureMemory() {
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        FileLearningCandidateStore candidateStore = new FileLearningCandidateStore(
                tempDir.resolve(".jobclaw").resolve("learning").toString()
        );
        MemoryTool tool = new MemoryTool(config, candidateStore);

        String result = tool.execute(
                "remember",
                "记录为教训：不要在 subtasks 还有 pending 项时输出最终总结，否则长任务会被误判完成。",
                null,
                null,
                "lesson,subtasks",
                "project",
                0.9
        );

        assertTrue(result.contains("Learning candidate created"));
        LearningCandidate candidate = candidateStore.list().get(0);
        assertEquals(LearningCandidateType.NEGATIVE_LESSON, candidate.getType());
        assertEquals(LearningCandidateStatus.PENDING, candidate.getStatus());
        assertNull(candidate.getDeliveryType());
        assertTrue(candidate.getTags().contains("negative_lesson"));
    }

    @Test
    void shouldTreatSingleRunSummaryAsReferenceMemoryOnly() {
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        FileLearningCandidateStore candidateStore = new FileLearningCandidateStore(
                tempDir.resolve(".jobclaw").resolve("learning").toString()
        );
        MemoryTool tool = new MemoryTool(config, candidateStore);

        String result = tool.execute(
                "remember",
                "总结经验：这次审查 PDF 时先看第一页作者，再看最后一页参考文献。",
                null,
                null,
                "workflow,pdf",
                "project",
                0.8
        );

        assertTrue(result.contains("Remembered durable memory"));
        assertTrue(candidateStore.list().isEmpty());
    }
}
