package io.jobclaw.tools;

import io.jobclaw.agent.AgentDefinition;
import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.AgentOrchestrator;
import io.jobclaw.agent.ExecutionEvent;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.agent.catalog.AgentCatalogService;
import io.jobclaw.agent.catalog.SqliteAgentCatalogStore;
import io.jobclaw.bus.MessageBus;
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
        AgentCatalogService catalogService = new AgentCatalogService(
                new SqliteAgentCatalogStore(tempDir.resolve("agents.db").toString())
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

        SpawnTool spawnTool = new SpawnTool(orchestrator, catalogService, new MessageBus(), new ExecutionTraceService());
        String response = spawnTool.spawn("analyze this jd", "JD Task", false, null, "jd analyst");

        assertTrue(response.contains("jd analyst"));
        assertTrue(response.contains("handled by jd analyst"));
        verify(orchestrator).processWithDefinition(anyString(), anyString(), any(AgentDefinition.class));
    }

    @Test
    void shouldPublishObservedEventsForPersistentAgentAsync() throws Exception {
        Path tempDir = Files.createTempDirectory("spawn-agent-async");
        AgentCatalogService catalogService = new AgentCatalogService(
                new SqliteAgentCatalogStore(tempDir.resolve("agents.db").toString())
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

        SpawnTool spawnTool = new SpawnTool(orchestrator, catalogService, new MessageBus(), traceService);
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "web:parent",
                null,
                "run-parent",
                null,
                "assistant",
                "Assistant"
        ));

        String response = spawnTool.spawn("investigate company", "Research Task", true, null, "research helper");
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
}
