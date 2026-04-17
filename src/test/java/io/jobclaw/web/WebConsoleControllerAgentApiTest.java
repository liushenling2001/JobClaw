package io.jobclaw.web;

import io.jobclaw.agent.AgentLoop;
import io.jobclaw.agent.AgentOrchestrator;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.agent.TaskHarnessService;
import io.jobclaw.agent.catalog.FileAgentCatalogStore;
import io.jobclaw.agent.catalog.AgentCatalogService;
import io.jobclaw.agent.profile.AgentProfile;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebConsoleControllerAgentApiTest {

    @Test
    void shouldCreateAndCloneCatalogAgentsViaApi() throws Exception {
        Path tempDir = Files.createTempDirectory("web-agent-api");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        AgentCatalogService catalogService = new AgentCatalogService(
                new FileAgentCatalogStore(tempDir.resolve(".jobclaw").resolve("agents").toString())
        );
        AgentProfileService profileService = new AgentProfileService(config, catalogService);
        WebConsoleController controller = new WebConsoleController(
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
                profileService,
                catalogService
        );

        WebConsoleController.AgentProfileUpsertRequest createRequest = new WebConsoleController.AgentProfileUpsertRequest();
        createRequest.setCode("doc_reviewer");
        createRequest.setDisplayName("Doc Reviewer");
        createRequest.setDescription("Review uploaded documents");
        createRequest.setAllowedTools(List.of("read_word", "read_pdf"));
        createRequest.setAllowedSkills(List.of("skills"));
        createRequest.setModelConfig(Map.of("model", "gpt-5.4", "provider", "openai", "timeoutMs", 120000L));

        ResponseEntity<?> createResponse = controller.createAgent(createRequest);
        assertEquals(200, createResponse.getStatusCode().value());
        AgentProfile created = (AgentProfile) createResponse.getBody();
        assertNotNull(created);
        assertEquals("doc_reviewer", created.code());
        assertEquals("openai", created.modelConfig().get("provider"));

        WebConsoleController.AgentProfileCloneRequest cloneRequest = new WebConsoleController.AgentProfileCloneRequest();
        cloneRequest.setCode("doc_reviewer_clone");
        cloneRequest.setDisplayName("Doc Reviewer Clone");

        ResponseEntity<?> cloneResponse = controller.cloneAgent("agent:doc_reviewer", cloneRequest);
        assertEquals(200, cloneResponse.getStatusCode().value());
        AgentProfile cloned = (AgentProfile) cloneResponse.getBody();
        assertNotNull(cloned);
        assertEquals("doc_reviewer_clone", cloned.code());
        assertTrue(cloned.allowedTools().contains("read_pdf"));
    }

    @Test
    void shouldUpdateAndDisableCatalogAgentViaApi() throws Exception {
        Path tempDir = Files.createTempDirectory("web-agent-update-api");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        AgentCatalogService catalogService = new AgentCatalogService(
                new FileAgentCatalogStore(tempDir.resolve(".jobclaw").resolve("agents").toString())
        );
        catalogService.createAgent(
                "reviewer_a",
                "Reviewer A",
                "desc",
                "prompt",
                List.of("Reviewer A"),
                List.of("read_file"),
                List.of(),
                Map.of("model", "qwen"),
                "agent:reviewer_a"
        );

        WebConsoleController controller = new WebConsoleController(
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
                catalogService
        );

        WebConsoleController.AgentProfileUpsertRequest updateRequest = new WebConsoleController.AgentProfileUpsertRequest();
        updateRequest.setDisplayName("Reviewer A+");
        updateRequest.setAllowedTools(List.of("read_file", "read_pdf"));
        updateRequest.setModelConfig(Map.of("model", "gpt-5.4-mini", "temperature", 0.2));

        ResponseEntity<?> updateResponse = controller.updateAgent("agent:reviewer_a", updateRequest);
        assertEquals(200, updateResponse.getStatusCode().value());
        AgentProfile updated = (AgentProfile) updateResponse.getBody();
        assertNotNull(updated);
        assertEquals("Reviewer A+", updated.displayName());
        assertTrue(updated.allowedTools().contains("read_pdf"));

        ResponseEntity<?> disableResponse = controller.disableAgent("agent:reviewer_a");
        assertEquals(200, disableResponse.getStatusCode().value());
        AgentProfile disabled = (AgentProfile) disableResponse.getBody();
        assertNotNull(disabled);
        assertEquals("disabled", disabled.status());
    }
}
