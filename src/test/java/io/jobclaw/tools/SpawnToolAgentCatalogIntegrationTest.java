package io.jobclaw.tools;

import io.jobclaw.agent.AgentDefinition;
import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.AgentOrchestrator;
import io.jobclaw.agent.ExecutionEvent;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.agent.TaskHarnessRun;
import io.jobclaw.agent.TaskHarnessService;
import io.jobclaw.agent.TaskHarnessSubtaskStatus;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        }).when(orchestrator).processWithDefinition(anyString(), anyString(), any(AgentDefinition.class), any());

        SpawnTool spawnTool = new SpawnTool(orchestrator, new AgentProfileService(config, catalogService), new MessageBus(), new ExecutionTraceService(), new TaskHarnessService(), config);
        String response = spawnTool.spawn("analyze this jd", "JD Task", false, null, "jd analyst", null, null);

        assertTrue(response.contains("jd analyst"));
        assertTrue(response.contains("handled by jd analyst"));
        verify(orchestrator).processWithDefinition(anyString(), anyString(), any(AgentDefinition.class), any());
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
    void shouldPublishObservedEventsForPersistentAgentSync() throws Exception {
        Path tempDir = Files.createTempDirectory("spawn-agent-sync-events");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        AgentCatalogService catalogService = new AgentCatalogService(
                new FileAgentCatalogStore(tempDir.resolve(".jobclaw").resolve("agents").toString())
        );
        AgentCatalogTool catalogTool = new AgentCatalogTool(catalogService);
        catalogTool.agentCatalog(
                "create",
                "file helper",
                "Inspect files",
                null,
                "file helper",
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
            callback.accept(new ExecutionEvent(
                    childSession,
                    ExecutionEvent.EventType.TOOL_START,
                    "read_pdf",
                    java.util.Map.of("toolName", "read_pdf")
            ));
            return "sync done";
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

        String response = spawnTool.spawn("inspect file", "File Task", false, null, "file helper", null, null);

        assertTrue(response.contains("sync done"));
        var events = traceService.getHistory("web:parent");
        assertTrue(events.stream().anyMatch(event -> event.getType() == ExecutionEvent.EventType.TOOL_START
                && "read_pdf".equals(event.getContent())
                && "File Task".equals(event.getMetadata().get("spawnTaskLabel"))));
        assertTrue(events.stream().allMatch(event -> "web:parent".equals(event.getSessionId())));
        assertTrue(childSessionRef.get() != null && childSessionRef.get().startsWith("spawn-"));
    }

    @Test
    void shouldInheritParentAgentDefinitionWhenSpawnRunsWithoutRoleOrAgent() throws Exception {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        doAnswer(invocation -> {
            AgentDefinition definition = invocation.getArgument(2);
            return definition.getConfig().getModel() + "|" + definition.getConfig().getProvider();
        }).when(orchestrator).processWithDefinition(anyString(), anyString(), any(AgentDefinition.class), any());

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
        verify(orchestrator).processWithDefinition(anyString(), anyString(), any(AgentDefinition.class), any());
    }

    @Test
    void shouldMarkTrackedSubtaskFailedWhenChildReturnsErrorText() throws Exception {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        doAnswer(invocation -> "Error: child failed (check network/API key)")
                .when(orchestrator).processWithDefinition(anyString(), anyString(), any(AgentDefinition.class), any());

        Config config = Config.defaultConfig();
        Path tempDir = Files.createTempDirectory("spawn-child-error");
        config.getAgent().setWorkspace(tempDir.toString());
        TaskHarnessService harnessService = new TaskHarnessService();
        TaskHarnessRun run = harnessService.startRun("web:parent", "run-parent", "batch task", null);
        AgentDefinition parentDefinition = AgentDefinition.builder()
                .code("parent-agent")
                .displayName("Parent Agent")
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

        SpawnTool spawnTool = new SpawnTool(
                orchestrator,
                new AgentProfileService(config, new AgentCatalogService(new FileAgentCatalogStore(tempDir.resolve(".jobclaw").resolve("agents").toString()))),
                new MessageBus(),
                new ExecutionTraceService(),
                harnessService,
                config
        );

        String response = spawnTool.spawn("check file", "File A", false, null, null, "file-a", null);

        assertTrue(response.contains("Sub-agent failed task"));
        assertEquals(TaskHarnessSubtaskStatus.FAILED, run.getSubtasks().get(0).status());
        assertEquals("transient_model_error", run.getSubtasks().get(0).metadata().get("failureType"));
        assertEquals(true, run.getSubtasks().get(0).metadata().get("retryable"));
        assertEquals(1, run.getSubtasks().get(0).metadata().get("retryCount"));
    }

    @Test
    void explicitTimeoutShouldOverrideAgentConfigTimeout() throws Exception {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        doAnswer(invocation -> {
            Thread.sleep(50);
            return "done";
        }).when(orchestrator).processWithDefinition(anyString(), anyString(), any(AgentDefinition.class), any());

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

    @Test
    void explicitTimeoutShouldNotReduceConfiguredSubtaskTimeout() throws Exception {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        doAnswer(invocation -> {
            Thread.sleep(50);
            return "done";
        }).when(orchestrator).processWithDefinition(anyString(), anyString(), any(AgentDefinition.class), any());

        Path tempDir = Files.createTempDirectory("spawn-timeout-floor");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        config.getAgent().setSubtaskTimeoutMs(200L);
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
                null,
                null
        );

        SpawnTool spawnTool = new SpawnTool(orchestrator, new AgentProfileService(config, catalogService), new MessageBus(),
                new ExecutionTraceService(), new TaskHarnessService(), config);

        String response = spawnTool.spawn("wait", "Slow Task", false, null, "slow-agent", null, 10L);

        assertTrue(response.contains("done"));
    }

    @Test
    void shouldUseRoleSubtaskTimeoutFromModelConfigWhenTimeoutMsIsAbsent() throws Exception {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        doAnswer(invocation -> {
            Thread.sleep(50);
            return "done";
        }).when(orchestrator).processWithDefinition(anyString(), anyString(), any(AgentDefinition.class), any());

        Path tempDir = Files.createTempDirectory("spawn-profile-subtask-timeout");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        config.getAgent().setSubtaskTimeoutMs(5_000L);
        AgentCatalogService catalogService = new AgentCatalogService(
                new FileAgentCatalogStore(tempDir.resolve(".jobclaw").resolve("agents").toString())
        );
        catalogService.createAgent(
                "short-agent",
                "Short Agent",
                "short",
                "prompt",
                java.util.List.of(),
                null,
                null,
                java.util.Map.of("subtaskTimeoutMs", 10L),
                null
        );

        SpawnTool spawnTool = new SpawnTool(orchestrator, new AgentProfileService(config, catalogService), new MessageBus(),
                new ExecutionTraceService(), new TaskHarnessService(), config);

        String response = spawnTool.spawn("wait", "Short Task", false, null, "short-agent", null, null);

        assertTrue(response.contains("Sub-agent failed task"));
        assertTrue(response.contains("Sub-agent timed out after 10 ms"));
    }

    @Test
    void shouldBoundSubtaskResultReturnedToParentContext() throws Exception {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        doAnswer(invocation -> "x".repeat(200))
                .when(orchestrator).processWithDefinition(anyString(), anyString(), any(AgentDefinition.class), any());

        Path tempDir = Files.createTempDirectory("spawn-result-bound");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        config.getAgent().setSubtaskResultMaxChars(50);
        AgentCatalogService catalogService = new AgentCatalogService(
                new FileAgentCatalogStore(tempDir.resolve(".jobclaw").resolve("agents").toString())
        );
        catalogService.createAgent(
                "brief-agent",
                "Brief Agent",
                "brief",
                "prompt",
                java.util.List.of(),
                null,
                null,
                null,
                null
        );

        SpawnTool spawnTool = new SpawnTool(orchestrator, new AgentProfileService(config, catalogService), new MessageBus(),
                new ExecutionTraceService(), new TaskHarnessService(), config);

        String response = spawnTool.spawn("produce long output", "Brief Task", false, null, "brief-agent", null, null);

        assertTrue(response.contains("Subtask result truncated for parent context"));
        assertTrue(response.contains("Child session"));
        assertTrue(response.length() < 500);
    }
}
