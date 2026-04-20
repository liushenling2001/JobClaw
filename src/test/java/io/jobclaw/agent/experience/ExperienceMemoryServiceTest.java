package io.jobclaw.agent.experience;

import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.learning.LearningCandidate;
import io.jobclaw.agent.learning.LearningCandidateStatus;
import io.jobclaw.agent.learning.LearningCandidateType;
import io.jobclaw.agent.planning.TaskPlanningMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceMemoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldApplyWorkflowCandidateAsExperienceMemory() {
        ExperienceMemoryService service = new ExperienceMemoryService(
                new FileExperienceMemoryStore(tempDir.toString())
        );

        service.applyAcceptedCandidate(candidate(LearningCandidateType.SKILL_UPDATE));

        List<ExperienceMemory> memories = service.listActive();
        assertEquals(1, memories.size());
        ExperienceMemory memory = memories.get(0);
        assertEquals(ExperienceMemoryType.WORKFLOW_EXPERIENCE, memory.getType());
        assertEquals(List.of("list_dir", "subtasks", "spawn"), memory.getToolSequence());
    }

    @Test
    void shouldApplyNegativeLessonAsAvoidRule() {
        ExperienceMemoryService service = new ExperienceMemoryService(
                new FileExperienceMemoryStore(tempDir.toString())
        );

        service.applyAcceptedCandidate(candidate(LearningCandidateType.NEGATIVE_LESSON));

        List<ExperienceMemory> memories = service.listActive();
        assertEquals(1, memories.size());
        ExperienceMemory memory = memories.get(0);
        assertEquals(ExperienceMemoryType.AVOID_RULE, memory.getType());
        assertTrue(memory.getAvoidRules().stream().anyMatch(rule -> rule.contains("pending subtasks")));
    }

    private LearningCandidate candidate(LearningCandidateType type) {
        LearningCandidate candidate = new LearningCandidate();
        candidate.setId("candidate-1");
        candidate.setType(type);
        candidate.setStatus(LearningCandidateStatus.ACCEPTED);
        candidate.setTitle("批量 PDF 审查经验");
        candidate.setReason("重复成功后可复用");
        candidate.setTaskInput("批量审查 PDF 文件");
        candidate.setPlanningMode(TaskPlanningMode.WORKLIST);
        candidate.setDeliveryType(DeliveryType.BATCH_RESULTS);
        candidate.setProposal("Use one-file-per-subtask workflow.");
        candidate.setConfidence(0.8);
        candidate.setMetadata(Map.of(
                "toolSequence", List.of("list_dir", "subtasks", "spawn"),
                "failureReason", "pending subtasks remained"
        ));
        candidate.setCreatedAt(Instant.now());
        candidate.setUpdatedAt(Instant.now());
        return candidate;
    }
}
