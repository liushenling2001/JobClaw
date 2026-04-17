package io.jobclaw.agent.profile;

import io.jobclaw.agent.catalog.AgentCatalogService;
import io.jobclaw.agent.catalog.FileAgentCatalogStore;
import io.jobclaw.config.Config;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentProfileServiceTest {

    @Test
    void shouldListMainRoleAndCatalogProfiles() throws Exception {
        Path tempDir = Files.createTempDirectory("agent-profile-service");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        AgentCatalogService catalogService = new AgentCatalogService(
                new FileAgentCatalogStore(tempDir.resolve(".jobclaw").resolve("agents").toString())
        );
        catalogService.createAgent(
                "doc_reviewer",
                "Doc Reviewer",
                "Review documents",
                "You review documents.",
                List.of("doc reviewer"),
                List.of("read_file", "skills"),
                List.of("format-review"),
                Map.of("model", "gpt-x"),
                "agent:doc_reviewer"
        );

        AgentProfileService service = new AgentProfileService(config, catalogService);
        List<AgentProfile> profiles = service.listProfiles();

        assertTrue(profiles.stream().anyMatch(profile -> AgentProfileService.MAIN_AGENT_ID.equals(profile.id())));
        assertTrue(profiles.stream().anyMatch(profile -> "role:reviewer".equals(profile.id())));
        assertTrue(profiles.stream().anyMatch(profile -> "agent:doc_reviewer".equals(profile.id())));
    }

    @Test
    void shouldResolveRoleAndCatalogProfilesById() throws Exception {
        Path tempDir = Files.createTempDirectory("agent-profile-resolve");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        AgentCatalogService catalogService = new AgentCatalogService(
                new FileAgentCatalogStore(tempDir.resolve(".jobclaw").resolve("agents").toString())
        );
        catalogService.createAgent(
                "doc_reviewer",
                "Doc Reviewer",
                "Review documents",
                "You review documents.",
                List.of("doc reviewer"),
                List.of("read_file"),
                List.of(),
                Map.of(),
                "agent:doc_reviewer"
        );

        AgentProfileService service = new AgentProfileService(config, catalogService);

        AgentProfile roleProfile = service.getProfile("role:reviewer").orElseThrow();
        AgentProfile catalogProfile = service.getProfile("agent:doc_reviewer").orElseThrow();
        AgentProfile mainProfile = service.getProfile(AgentProfileService.MAIN_AGENT_ID).orElseThrow();

        assertEquals(AgentProfileKind.ROLE_TEMPLATE, roleProfile.kind());
        assertEquals(AgentProfileKind.CATALOG_AGENT, catalogProfile.kind());
        assertEquals(AgentProfileKind.MAIN_AGENT, mainProfile.kind());
        assertEquals("Doc Reviewer", catalogProfile.displayName());
    }

    @Test
    void shouldResolveSpawnRuntimeForDefaultRoleAndCatalogAgent() throws Exception {
        Path tempDir = Files.createTempDirectory("agent-profile-runtime");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        AgentCatalogService catalogService = new AgentCatalogService(
                new FileAgentCatalogStore(tempDir.resolve(".jobclaw").resolve("agents").toString())
        );
        catalogService.createAgent(
                "doc_reviewer",
                "Doc Reviewer",
                "Review documents",
                "You review documents.",
                List.of("doc reviewer"),
                List.of("read_file"),
                List.of("skill-a"),
                Map.of(),
                "agent:doc_reviewer"
        );

        AgentProfileService service = new AgentProfileService(config, catalogService);

        ResolvedAgentRuntime inherited = service.resolveRuntime(null, null).orElseThrow();
        ResolvedAgentRuntime roleRuntime = service.resolveRuntime("reviewer", null).orElseThrow();
        ResolvedAgentRuntime agentRuntime = service.resolveRuntime(null, "doc_reviewer").orElseThrow();

        assertTrue(inherited.inheritedFromParent());
        assertEquals(AgentProfileKind.MAIN_AGENT, inherited.profile().kind());
        assertEquals(AgentProfileKind.ROLE_TEMPLATE, roleRuntime.profile().kind());
        assertEquals("reviewer", roleRuntime.definition().getCode());
        assertEquals(AgentProfileKind.CATALOG_AGENT, agentRuntime.profile().kind());
        assertEquals("Doc Reviewer", agentRuntime.definition().getDisplayName());
    }

    @Test
    void shouldSeedRoleProfilesWithMainAgentRuntimeConfig() throws Exception {
        Path tempDir = Files.createTempDirectory("agent-profile-role-seed");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        config.getAgent().setProvider("openai");
        config.getAgent().setModel("gemma-4-e2b");
        config.getAgent().setSubtaskTimeoutMs(300000L);
        AgentCatalogService catalogService = new AgentCatalogService(
                new FileAgentCatalogStore(tempDir.resolve(".jobclaw").resolve("agents").toString())
        );

        AgentProfileService service = new AgentProfileService(config, catalogService);
        AgentProfile roleProfile = service.getProfile("role:reviewer").orElseThrow();

        assertEquals("openai", roleProfile.modelConfig().get("provider"));
        assertEquals("gemma-4-e2b", roleProfile.modelConfig().get("model"));
        assertEquals(300000L, ((Number) roleProfile.modelConfig().get("subtaskTimeoutMs")).longValue());
    }

    @Test
    void shouldNotOverwriteExplicitRoleRuntimeOverrides() throws Exception {
        Path tempDir = Files.createTempDirectory("agent-profile-role-preserve");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        AgentCatalogService catalogService = new AgentCatalogService(
                new FileAgentCatalogStore(tempDir.resolve(".jobclaw").resolve("agents").toString())
        );
        catalogService.createAgent(
                "reviewer",
                "Reviewer",
                "Existing reviewer",
                "Existing reviewer prompt",
                List.of("reviewer"),
                List.of(),
                List.of(),
                Map.of("provider", "custom-provider", "model", "custom-model"),
                "role:reviewer"
        );

        AgentProfileService service = new AgentProfileService(config, catalogService);
        AgentProfile roleProfile = service.getProfile("role:reviewer").orElseThrow();

        assertEquals("custom-provider", roleProfile.modelConfig().get("provider"));
        assertEquals("custom-model", roleProfile.modelConfig().get("model"));
        assertEquals(config.getAgent().getSubtaskTimeoutMs(), ((Number) roleProfile.modelConfig().get("subtaskTimeoutMs")).longValue());
    }
}
