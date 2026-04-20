package io.jobclaw.web;

import io.jobclaw.agent.AgentLoop;
import io.jobclaw.agent.AgentOrchestrator;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.agent.TaskHarnessService;
import io.jobclaw.agent.catalog.AgentCatalogService;
import io.jobclaw.agent.catalog.FileAgentCatalogStore;
import io.jobclaw.agent.experience.ExperienceMemoryService;
import io.jobclaw.agent.experience.FileExperienceMemoryStore;
import io.jobclaw.agent.learning.LearningCandidateService;
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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebConsoleControllerSessionsApiTest {

    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings("unchecked")
    void shouldDefaultSessionListToWebChannelAndSupportAllChannels() {
        SessionManager sessionManager = new SessionManager(tempDir.resolve("sessions").toString());
        sessionManager.addMessage("web:default", "user", "hello web");
        sessionManager.addMessage("feishu:chat-a", "user", "hello feishu");

        WebConsoleController controller = controller(sessionManager);

        Map<String, Object> webOnly = controller.getSessions(null, false, 1, 20).getBody();
        List<Map<String, Object>> webItems = (List<Map<String, Object>>) webOnly.get("items");
        assertEquals(1, webItems.size());
        assertEquals("web:default", webItems.get(0).get("key"));
        assertEquals("web", webItems.get(0).get("channel"));
        assertEquals(1, webOnly.get("total"));
        assertEquals("web", webOnly.get("channel"));

        Map<String, Object> all = controller.getSessions(null, true, 1, 20).getBody();
        List<Map<String, Object>> allItems = (List<Map<String, Object>>) all.get("items");
        assertEquals(2, allItems.size());
        assertEquals(2, all.get("total"));
        assertTrue(allItems.stream().anyMatch(item -> "feishu:chat-a".equals(item.get("key"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPaginateSessionList() {
        SessionManager sessionManager = new SessionManager(tempDir.resolve("sessions").toString());
        sessionManager.addMessage("web:a", "user", "a");
        sessionManager.addMessage("web:b", "user", "b");
        sessionManager.addMessage("web:c", "user", "c");

        Map<String, Object> page = controller(sessionManager).getSessions("web", false, 2, 2).getBody();
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");

        assertEquals(1, items.size());
        assertEquals(3, page.get("total"));
        assertEquals(2, page.get("page"));
        assertEquals(2, page.get("totalPages"));
    }

    private WebConsoleController controller(SessionManager sessionManager) {
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        AgentCatalogService catalogService = new AgentCatalogService(
                new FileAgentCatalogStore(tempDir.resolve(".jobclaw").resolve("agents").toString())
        );
        return new WebConsoleController(
                config,
                sessionManager,
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
                mock(LearningCandidateService.class),
                new ExperienceMemoryService(new FileExperienceMemoryStore(tempDir.resolve(".jobclaw").resolve("experience").toString()))
        );
    }
}
