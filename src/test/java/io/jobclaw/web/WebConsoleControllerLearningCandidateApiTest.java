package io.jobclaw.web;

import io.jobclaw.agent.AgentLoop;
import io.jobclaw.agent.AgentOrchestrator;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.agent.TaskHarnessService;
import io.jobclaw.agent.catalog.AgentCatalogService;
import io.jobclaw.agent.catalog.FileAgentCatalogStore;
import io.jobclaw.agent.experience.ExperienceMemoryService;
import io.jobclaw.agent.experience.FileExperienceMemoryStore;
import io.jobclaw.agent.learning.FileLearningCandidateStore;
import io.jobclaw.agent.learning.LearningCandidate;
import io.jobclaw.agent.learning.LearningCandidateService;
import io.jobclaw.agent.learning.LearningCandidateStatus;
import io.jobclaw.agent.learning.LearningCandidateType;
import io.jobclaw.agent.profile.AgentProfileService;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.config.Config;
import io.jobclaw.cron.CronService;
import io.jobclaw.retrieval.RetrievalService;
import io.jobclaw.security.SecurityGuard;
import io.jobclaw.session.SessionManager;
import io.jobclaw.skills.SkillsService;
import io.jobclaw.stats.TokenUsageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebConsoleControllerLearningCandidateApiTest {

    @Test
    void shouldListAndUpdateLearningCandidatesViaApi() throws Exception {
        Path tempDir = Files.createTempDirectory("web-learning-api");
        LearningCandidateService learningService = new LearningCandidateService(
                new FileLearningCandidateStore(tempDir.resolve(".jobclaw").resolve("learning").toString()),
                new ExperienceMemoryService(new FileExperienceMemoryStore(tempDir.resolve(".jobclaw").resolve("experience").toString()))
        );
        LearningCandidate candidate = candidate("candidate-a");
        learningService.recordSuccessfulRun(null);
        new FileLearningCandidateStore(tempDir.resolve(".jobclaw").resolve("learning").toString())
                .saveAll(List.of(candidate));

        WebConsoleController controller = controller(tempDir, learningService);

        ResponseEntity<?> listResponse = controller.listLearningCandidates("pending");
        assertEquals(200, listResponse.getStatusCode().value());
        List<?> listed = (List<?>) listResponse.getBody();
        assertNotNull(listed);
        assertEquals(1, listed.size());

        ResponseEntity<?> acceptResponse = controller.acceptLearningCandidate("candidate-a");
        assertEquals(200, acceptResponse.getStatusCode().value());
        LearningCandidate accepted = (LearningCandidate) acceptResponse.getBody();
        assertNotNull(accepted);
        assertEquals(LearningCandidateStatus.ACCEPTED, accepted.getStatus());
        ResponseEntity<?> memoriesResponse = controller.listExperienceMemories();
        assertEquals(200, memoriesResponse.getStatusCode().value());
        assertTrue(((List<?>) memoriesResponse.getBody()).size() >= 1);

        ResponseEntity<?> rejectMissingResponse = controller.rejectLearningCandidate("missing");
        assertEquals(404, rejectMissingResponse.getStatusCode().value());
    }

    @Test
    void shouldReadLatestExperienceReviewViaApi() throws Exception {
        Path tempDir = Files.createTempDirectory("web-experience-api");
        Path latest = tempDir.resolve(".jobclaw").resolve("experience").resolve("latest.md");
        Files.createDirectories(latest.getParent());
        Files.writeString(latest, "# Latest Review\n\ncontent");
        WebConsoleController controller = controller(tempDir, new LearningCandidateService(
                new FileLearningCandidateStore(tempDir.resolve(".jobclaw").resolve("learning").toString()),
                new ExperienceMemoryService(new FileExperienceMemoryStore(tempDir.resolve(".jobclaw").resolve("experience").toString()))
        ));

        ResponseEntity<?> response = controller.getLatestExperienceReview();

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("exists"));
        assertTrue(body.get("content").toString().contains("Latest Review"));
    }

    @Test
    void shouldRejectUnsupportedStatusFilter() throws Exception {
        Path tempDir = Files.createTempDirectory("web-learning-api-status");
        WebConsoleController controller = controller(tempDir, new LearningCandidateService(
                new FileLearningCandidateStore(tempDir.resolve(".jobclaw").resolve("learning").toString())
        ));

        ResponseEntity<?> response = controller.listLearningCandidates("unknown");

        assertEquals(400, response.getStatusCode().value());
    }

    private WebConsoleController controller(Path tempDir, LearningCandidateService learningService) {
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        AgentCatalogService catalogService = new AgentCatalogService(
                new FileAgentCatalogStore(tempDir.resolve(".jobclaw").resolve("agents").toString())
        );
        return new WebConsoleController(
                config,
                new SessionManager(),
                mock(AgentLoop.class),
                mock(AgentOrchestrator.class),
                new MessageBus(),
                new ExecutionTraceService(),
                new TaskHarnessService(),
                mock(CronService.class),
                mock(SkillsService.class),
                mock(io.jobclaw.mcp.MCPService.class),
                mock(TokenUsageService.class),
                mock(SecurityGuard.class),
                mock(RetrievalService.class),
                new AgentProfileService(config, catalogService),
                catalogService,
                learningService,
                new ExperienceMemoryService(new FileExperienceMemoryStore(tempDir.resolve(".jobclaw").resolve("experience").toString()))
        );
    }

    private LearningCandidate candidate(String id) {
        LearningCandidate candidate = new LearningCandidate();
        candidate.setId(id);
        candidate.setType(LearningCandidateType.WORKFLOW);
        candidate.setStatus(LearningCandidateStatus.PENDING);
        candidate.setTitle("Workflow candidate");
        candidate.setProposal("Use list_dir -> subtasks -> spawn");
        candidate.setCreatedAt(Instant.now());
        candidate.setUpdatedAt(Instant.now());
        return candidate;
    }
}
