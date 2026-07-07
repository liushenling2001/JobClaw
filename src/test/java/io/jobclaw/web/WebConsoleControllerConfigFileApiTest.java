package io.jobclaw.web;

import io.jobclaw.agent.AgentLoop;
import io.jobclaw.agent.AgentOrchestrator;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.agent.catalog.AgentCatalogService;
import io.jobclaw.agent.catalog.FileAgentCatalogStore;
import io.jobclaw.agent.experience.ExperienceMemoryService;
import io.jobclaw.agent.experience.FileExperienceMemoryStore;
import io.jobclaw.agent.learning.LearningCandidateService;
import io.jobclaw.agent.profile.AgentProfileService;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.config.Config;
import io.jobclaw.config.ConfigLoader;
import io.jobclaw.cron.CronService;
import io.jobclaw.retrieval.RetrievalService;
import io.jobclaw.runtime.provider.ResolvedProviderConfig;
import io.jobclaw.security.SecurityGuard;
import io.jobclaw.session.SessionManager;
import io.jobclaw.skills.SkillsService;
import io.jobclaw.stats.TokenUsageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebConsoleControllerConfigFileApiTest {

    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings("unchecked")
    void shouldCreateConfigFileOnlyWhenMissing() throws Exception {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            WebConsoleController controller = controller();
            Path configPath = Path.of(ConfigLoader.getConfigPath());
            assertFalse(Files.exists(configPath));

            ResponseEntity<Map<String, Object>> statusResponse = controller.getConfigFileStatus();
            Map<String, Object> status = statusResponse.getBody();
            assertEquals(200, statusResponse.getStatusCode().value());
            assertEquals(false, status.get("exists"));
            assertEquals(true, status.get("canCreate"));

            ResponseEntity<Map<String, Object>> createResponse = controller.createConfigFile();
            Map<String, Object> created = createResponse.getBody();
            assertEquals(200, createResponse.getStatusCode().value());
            assertEquals(true, created.get("success"));
            assertTrue(Files.exists(configPath));

            String originalContent = Files.readString(configPath);
            ResponseEntity<Map<String, Object>> duplicateResponse = controller.createConfigFile();
            Map<String, Object> duplicate = duplicateResponse.getBody();
            assertEquals(409, duplicateResponse.getStatusCode().value());
            assertEquals("config file already exists", duplicate.get("error"));
            assertEquals(originalContent, Files.readString(configPath));
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void shouldHotReloadMainAgentClientAfterModelConfigUpdate() throws Exception {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            Config config = Config.defaultConfig();
            config.getAgent().setWorkspace(tempDir.resolve("workspace").toString());
            AgentLoop agentLoop = mock(AgentLoop.class);
            when(agentLoop.reloadDefaultClient()).thenReturn(new ResolvedProviderConfig(
                    "ollama",
                    "llama3.1",
                    "",
                    "http://localhost:11434/v1",
                    "http://localhost:11434/v1",
                    true,
                    false
            ));

            WebConsoleController controller = controller(config, agentLoop);
            WebConsoleController.UpdateModelRequest request = new WebConsoleController.UpdateModelRequest();
            request.setModel("llama3.1");
            request.setProvider("ollama");

            ResponseEntity<Map<String, Object>> response = controller.updateConfigModel(request);
            Map<String, Object> body = response.getBody();

            assertEquals(200, response.getStatusCode().value());
            assertEquals(true, body.get("success"));
            assertEquals(true, body.get("clientReloaded"));
            assertEquals("ollama", body.get("reloadedProvider"));
            assertEquals("llama3.1", body.get("reloadedModel"));
            assertTrue(Files.exists(Path.of(ConfigLoader.getConfigPath())));
            verify(agentLoop).reloadDefaultClient();
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldUpdateMainAgentConfigThroughAgentApiAndHotReloadClient() throws Exception {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            Config config = Config.defaultConfig();
            config.getAgent().setWorkspace(tempDir.resolve("workspace").toString());
            AgentLoop agentLoop = mock(AgentLoop.class);
            when(agentLoop.reloadDefaultClient()).thenReturn(new ResolvedProviderConfig(
                    "ollama",
                    "llama3.1",
                    "",
                    "http://localhost:11434/v1",
                    "http://localhost:11434/v1",
                    true,
                    false
            ));

            WebConsoleController controller = controller(config, agentLoop);
            WebConsoleController.AgentProfileUpsertRequest request = new WebConsoleController.AgentProfileUpsertRequest();
            request.setModelConfig(Map.of(
                    "provider", "ollama",
                    "model", "llama3.1",
                    "apiBase", "http://localhost:11434/v1",
                    "apiKey", "sk-local-test",
                    "temperature", 0.2,
                    "maxTokens", 2048,
                    "toolCallTimeoutSeconds", 120,
                    "childAgentTimeoutMs", 600000
            ));

            ResponseEntity<?> response = controller.updateAgent("main:assistant", request);
            Map<String, Object> body = (Map<String, Object>) response.getBody();

            assertEquals(200, response.getStatusCode().value());
            assertEquals(true, body.get("success"));
            assertEquals(true, body.get("clientReloaded"));
            assertEquals("ollama", body.get("reloadedProvider"));
            assertEquals("llama3.1", body.get("reloadedModel"));
            assertEquals("ollama", config.getAgent().getProvider());
            assertEquals("llama3.1", config.getAgent().getModel());
            assertEquals(0.2, config.getAgent().getTemperature());
            assertEquals(2048, config.getAgent().getMaxTokens());
            assertEquals(120, config.getAgent().getToolCallTimeoutSeconds());
            assertEquals(600000L, config.getAgent().getChildAgentTimeoutMs());
            assertEquals("http://localhost:11434/v1", config.getProviders().getOllama().getApiBase());
            assertEquals("sk-local-test", config.getProviders().getOllama().getApiKey());
            assertTrue(Files.exists(Path.of(ConfigLoader.getConfigPath())));
            verify(agentLoop).reloadDefaultClient();
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    private WebConsoleController controller() {
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.resolve("workspace").toString());
        return controller(config, mock(AgentLoop.class));
    }

    private WebConsoleController controller(Config config, AgentLoop agentLoop) {
        AgentCatalogService catalogService = new AgentCatalogService(
                new FileAgentCatalogStore(tempDir.resolve(".jobclaw").resolve("agents").toString())
        );
        return new WebConsoleController(
                config,
                new SessionManager(tempDir.resolve("sessions").toString()),
                agentLoop,
                mock(AgentOrchestrator.class),
                new MessageBus(),
                new ExecutionTraceService(),
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
