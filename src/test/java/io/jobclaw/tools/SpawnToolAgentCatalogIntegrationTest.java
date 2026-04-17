package io.jobclaw.tools;

import io.jobclaw.agent.AgentDefinition;
import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.AgentOrchestrator;
import io.jobclaw.agent.ExecutionEvent;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.agent.TaskHarnessService;
import io.jobclaw.agent.profile.AgentProfileService;
import io.jobclaw.agent.catalog.AgentCatalogService;
import io.jobclaw.agent.catalog.FileAgentCatalogStore;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SpawnToolAgentCatalogIntegrationTest {

    @AfterEach
    void tearDown() {
        AgentExecutionContext.clear();
    }

    @Test
    void shouldInvokePersistentAgentViaSpawnSync() throws Exception {
        Path tempDir = Files.createTempDirectory("spawn-agent-sync");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        AgentCatalogService catalogService = new AgentCatalogService(
                new FileAgentCatalogStore(tempDir.resolve(".jobclaw").resolve("agents").toString())
        );
        AgentCatalogTool catalogTool = new AgentCatalogTool(catalogService);
        catalogTool.agentCatalog(
                "create",
                "jd analyst",
                "Analyze job descriptions",
                null,
                "jd analyst,jd helper",
                "read_file,list_dir"
        );

        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        doAnswer(invocation -> {
            AgentDefinition definition = invocation.getArgument(2);
            return "handled by " + definition.getDisplayName();
        }).when(orchestrator).processWithDefinition(anyString(), anyString(), any(AgentDefinition.class));

        SpawnTool spawnTool = new SpawnTool(orchestrator, new AgentProfileService(config, catalogService), new MessageBus(), new ExecutionTraceService(), new TaskHarnessService(), config);
        String response = spawnTool.spawn("analyze this jd", "JD Task", false, null, "jd analyst", null, null);

        assertTrue(response.contains("jd analyst"));
        assertTrue(response.contains("handled by jd analyst"));
        verify(orchestrator).processWithDefinition(anyString(), anyString(), any(AgentDefinition.class));
    }

    @Test
    void shouldPublishObservedEventsForPersistentAgentAsync() throws Exception {
        Path tempDir = Files.createTempDirectory("spawn-agent-async");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        AgentCatalogService catalogService = new AgentCatalogService(
                new FileAgentCatalogStore(tempDir.resolve(".jobclaw").resolve("agents").toString())
        );
        AgentCatalogTool catalogTool = new AgentCatalogTool(catalogService);
        catalogTool.agentCatalog(
                "create",
                "research helper",
                "Investigate company information",
                null,
                "research helper",
                "read_file,list_dir"
        );

        ExecutionTraceService traceService = new ExecutionTraceService();
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AtomicReference<String> childSessionRef = new AtomicReference<>();

        doAnswer(invocation -> {
            String childSession = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<ExecutionEvent> callback = invocation.getArgument(3);
            childSessionRef.set(childSession);
            callback.accept(new ExecutionEvent(childSession, ExecutionEvent.EventType.CUSTOM, "phase one"));
            return "async done";
        }).when(orchestrator).processWithDefinition(anyString(), anyString(), any(AgentDefinition.class), any());

        SpawnTool spawnTool = new SpawnTool(orchestrator, new AgentProfileService(config, catalogService), new MessageBus(), traceService, new TaskHarnessService(), config);
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "web:parent",
                null,
                "run-parent",
                null,
                "assistant",
                "Assistant",
                null
        ));

        String response = spawnTool.spawn("investigate company", "Research Task", true, null, "research helper", null, null);
        assertTrue(response.contains("Run ID"));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && traceService.getHistory("web:parent").size() < 3) {
            Thread.sleep(50);
        }

        var events = traceService.getHistory("web:parent");
        assertTrue(events.stream().anyMatch(event -> "running".equals(event.getMetadata().get("asyncTaskStatus"))));
        assertTrue(events.stream().anyMatch(event -> "completed".equals(event.getMetadata().get("asyncTaskStatus"))));
        assertTrue(events.stream().anyMatch(event -> "phase one".equals(event.getContent())));
        assertTrue(events.stream().allMatch(event -> "run-parent".equals(event.getParentRunId()) || event.getParentRunId() == null));
        assertTrue(childSessionRef.get() != null && childSessionRef.get().startsWith("spawn-"));
    }

    @Test
    void shouldInheritParentAgentDefinitionWhenSpawnRunsWithoutRoleOrAgent() throws Exception {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        doAnswer(invocation -> {
            AgentDefinition definition = invocation.getArgument(2);
            return definition.getConfig().getModel() + "|" + definition.getConfig().getProvider();
        }).when(orchestrator).processWithDefinition(anyString(), anyString(), any(AgentDefinition.class));

        Config config = Config.defaultConfig();
        Path tempDir = Files.createTempDirectory("spawn-inherit");
        config.getAgent().setWorkspace(tempDir.toString());
        SpawnTool spawnTool = new SpawnTool(
                orchestrator,
                new AgentProfileService(config, new AgentCatalogService(new FileAgentCatalogStore(tempDir.resolve(".jobclaw").resolve("agents").toString()))),
                new MessageBus(),
                new ExecutionTraceService(),
                new TaskHarnessService(),
                config
        );

        AgentDefinition.AgentConfig parentConfig = new AgentDefinition.AgentConfig();
        parentConfig.setModel("parent-model");
        parentConfig.setProvider("openai");
        AgentDefinition parentDefinition = AgentDefinition.builder()
                .code("parent-agent")
                .displayName("Parent Agent")
                .systemPrompt("parent prompt")
                .config(parentConfig)
                .build();

        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "web:parent",
                null,
                "run-parent",
                null,
                "parent-agent",
                "Parent Agent",
                parentDefinition
        ));

        String response = spawnTool.spawn("keep working", "Inherited Task", false, null, null, null, null);

        assertTrue(response.contains("parent-model|openai"));
        verify(orchestrator).processWithDefinition(anyString(), anyString(), any(AgentDefinition.class));
    }

    @Test
    void explicitTimeoutShouldOverrideAgentConfigTimeout() throws Exception {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        doAnswer(invocation -> {
            Thread.sleep(50);
            return "done";
        }).when(orchestrator).processWithDefinition(anyString(), anyString(), any(AgentDefinition.class));

        Path tempDir = Files.createTempDirectory("spawn-timeout");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        AgentCatalogService catalogService = new AgentCatalogService(
                new FileAgentCatalogStore(tempDir.resolve(".jobclaw").resolve("agents").toString())
        );
        catalogService.createAgent(
                "slow-agent",
                "Slow Agent",
                "slow",
                "prompt",
                java.util.List.of(),
                null,
                null,
                java.util.Map.of("timeoutMs", 10L),
                null
        );

        SpawnTool spawnTool = new SpawnTool(orchestrator, new AgentProfileService(config, catalogService), new MessageBus(),
                new ExecutionTraceService(), new TaskHarnessService(), config);

        String response = spawnTool.spawn("wait", "Slow Task", false, null, "slow-agent", null, 200L);

        assertTrue(response.contains("done"));
    }
}
