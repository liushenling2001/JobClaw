package io.jobclaw.tools;

import io.jobclaw.agent.AgentDefinition;
import io.jobclaw.agent.AgentLoop;
import io.jobclaw.agent.AgentRegistry;
import io.jobclaw.agent.BoardEventSummarizer;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.agent.catalog.AgentCatalogService;
import io.jobclaw.agent.catalog.SqliteAgentCatalogStore;
import io.jobclaw.board.SharedBoardService;
import io.jobclaw.board.file.FileSharedBoardService;
import io.jobclaw.config.Config;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollaborateToolPersistentAgentTest {

    @Test
    void shouldResolvePersistentAgentsInSequentialCollaboration() throws Exception {
        Path tempDir = Files.createTempDirectory("collaborate-persistent-agent");
        AgentCatalogService catalogService = new AgentCatalogService(
                new SqliteAgentCatalogStore(tempDir.resolve("agents.db").toString())
        );
        SharedBoardService sharedBoardService = new FileSharedBoardService(tempDir.resolve("boards").toString());
        catalogService.createAgent(
                "jd_analyst",
                "jd analyst",
                "Analyze job descriptions",
                "You analyze job descriptions.",
                java.util.List.of("jd analyst"),
                java.util.List.of("read_file"),
                java.util.List.of(),
                java.util.Map.of(),
                "agent:jd_analyst"
        );

        AgentLoop loop = mock(AgentLoop.class);
        when(loop.processWithDefinition(anyString(), anyString(), any(AgentDefinition.class)))
                .thenAnswer(invocation -> {
                    AgentDefinition definition = invocation.getArgument(2);
                    return "processed by " + definition.getDisplayName();
                });

        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.getOrCreateAgent(any(AgentDefinition.class), anyString())).thenReturn(loop);

        CollaborateTool tool = new CollaborateTool(
                registry,
                catalogService,
                sharedBoardService,
                new ExecutionTraceService(),
                new BoardEventSummarizer(),
                Config.defaultConfig()
        );

        String result = tool.collaborate(
                "SEQUENTIAL",
                "analyze this jd",
                "[{\"agent\":\"jd analyst\"}]",
                1,
                1000L
        );

        assertTrue(result.contains("jd analyst"));
        assertTrue(result.contains("processed by jd analyst"));
    }

    @Test
    void shouldRejectPromptDefinedTemporaryAgents() {
        AgentRegistry registry = mock(AgentRegistry.class);
        SharedBoardService sharedBoardService = new FileSharedBoardService(Path.of(System.getProperty("java.io.tmpdir"), "collab-prompt-reject-boards").toString());
        CollaborateTool tool = new CollaborateTool(
                registry,
                new AgentCatalogService(new SqliteAgentCatalogStore(Path.of(System.getProperty("java.io.tmpdir"), "collab-prompt-reject.db").toString())),
                sharedBoardService,
                new ExecutionTraceService(),
                new BoardEventSummarizer(),
                Config.defaultConfig()
        );

        String result = tool.collaborate(
                "TEAM",
                "do work",
                "[{\"name\":\"temp-helper\",\"prompt\":\"do something\"}]",
                1,
                1000L
        );

        assertTrue(result.contains("Temporary prompt-defined agents are not supported"));
        assertTrue(result.contains("agent_catalog(action='create'"));
    }

    @Test
    void shouldSuggestCreatingMissingPersistentAgent() {
        AgentRegistry registry = mock(AgentRegistry.class);
        SharedBoardService sharedBoardService = new FileSharedBoardService(Path.of(System.getProperty("java.io.tmpdir"), "collab-missing-agent-boards").toString());
        CollaborateTool tool = new CollaborateTool(
                registry,
                new AgentCatalogService(new SqliteAgentCatalogStore(Path.of(System.getProperty("java.io.tmpdir"), "collab-missing-agent.db").toString())),
                sharedBoardService,
                new ExecutionTraceService(),
                new BoardEventSummarizer(),
                Config.defaultConfig()
        );

        String result = tool.collaborate(
                "TEAM",
                "do work",
                "[{\"agent\":\"missing-specialist\"}]",
                1,
                1000L
        );

        assertTrue(result.contains("Persistent agent `missing-specialist` was not found"));
        assertTrue(result.contains("agent_catalog(action='create'"));
    }

    @Test
    void shouldWriteTeamOutputsToSharedBoard() throws Exception {
        Path tempDir = Files.createTempDirectory("collaborate-team-board");
        SharedBoardService sharedBoardService = new FileSharedBoardService(tempDir.resolve("boards").toString());

        AgentLoop loop = mock(AgentLoop.class);
        when(loop.processWithDefinition(anyString(), anyString(), any(AgentDefinition.class)))
                .thenAnswer(invocation -> {
                    AgentDefinition definition = invocation.getArgument(2);
                    return "processed by " + definition.getDisplayName();
                });

        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.getOrCreateAgent(any(AgentDefinition.class), anyString())).thenReturn(loop);

        CollaborateTool tool = new CollaborateTool(
                registry,
                new AgentCatalogService(new SqliteAgentCatalogStore(tempDir.resolve("agents.db").toString())),
                sharedBoardService,
                new ExecutionTraceService(),
                new BoardEventSummarizer(),
                Config.defaultConfig()
        );

        String result = tool.collaborate(
                "TEAM",
                "analyze and summarize",
                "[{\"role\":\"coder\"},{\"role\":\"researcher\"}]",
                1,
                1000L
        );

        assertTrue(result.contains("Board ID"));
        String boardId = extractBoardId(result);
        var entries = sharedBoardService.readEntries(boardId, 20);
        assertTrue(entries.stream().anyMatch(entry -> "task".equals(entry.entryType())));
        assertTrue(entries.stream().anyMatch(entry -> "artifact".equals(entry.entryType()) && entry.content().contains("processed by")));
        assertTrue(entries.stream().anyMatch(entry -> "summary".equals(entry.entryType())));
    }

    @Test
    void shouldWriteSequentialOutputsToSharedBoard() throws Exception {
        Path tempDir = Files.createTempDirectory("collaborate-sequential-board");
        SharedBoardService sharedBoardService = new FileSharedBoardService(tempDir.resolve("boards").toString());

        AgentLoop loop = mock(AgentLoop.class);
        when(loop.processWithDefinition(anyString(), anyString(), any(AgentDefinition.class)))
                .thenAnswer(invocation -> {
                    AgentDefinition definition = invocation.getArgument(2);
                    return "sequential output by " + definition.getDisplayName();
                });

        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.getOrCreateAgent(any(AgentDefinition.class), anyString())).thenReturn(loop);

        CollaborateTool tool = new CollaborateTool(
                registry,
                new AgentCatalogService(new SqliteAgentCatalogStore(tempDir.resolve("agents.db").toString())),
                sharedBoardService,
                new ExecutionTraceService(),
                new BoardEventSummarizer(),
                Config.defaultConfig()
        );

        String result = tool.collaborate(
                "SEQUENTIAL",
                "complete pipeline",
                "[{\"role\":\"planner\"},{\"role\":\"coder\"}]",
                1,
                1000L
        );

        assertTrue(result.contains("Board ID"));
        String boardId = extractBoardId(result);
        var entries = sharedBoardService.readEntries(boardId, 20);
        assertTrue(entries.stream().anyMatch(entry -> "task".equals(entry.entryType())));
        assertTrue(entries.stream().anyMatch(entry -> "artifact".equals(entry.entryType()) && entry.content().contains("sequential output by")));
    }

    @Test
    void shouldWriteDebateOutputsToSharedBoard() throws Exception {
        Path tempDir = Files.createTempDirectory("collaborate-debate-board");
        SharedBoardService sharedBoardService = new FileSharedBoardService(tempDir.resolve("boards").toString());

        AgentLoop loop = mock(AgentLoop.class);
        when(loop.processWithDefinition(anyString(), anyString(), any(AgentDefinition.class)))
                .thenAnswer(invocation -> {
                    AgentDefinition definition = invocation.getArgument(2);
                    if ("writer".equalsIgnoreCase(definition.getCode())) {
                        return "debate summary";
                    }
                    return "debate output by " + definition.getDisplayName();
                });

        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.getOrCreateAgent(any(AgentDefinition.class), anyString())).thenReturn(loop);

        CollaborateTool tool = new CollaborateTool(
                registry,
                new AgentCatalogService(new SqliteAgentCatalogStore(tempDir.resolve("agents.db").toString())),
                sharedBoardService,
                new ExecutionTraceService(),
                new BoardEventSummarizer(),
                Config.defaultConfig()
        );

        String result = tool.collaborate(
                "DEBATE",
                "should we adopt strategy A",
                "[{\"role\":\"researcher\"},{\"role\":\"reviewer\"}]",
                2,
                1000L
        );

        assertTrue(result.contains("Board ID"));
        String boardId = extractBoardId(result);
        var entries = sharedBoardService.readEntries(boardId, 30);
        assertTrue(entries.stream().anyMatch(entry -> "task".equals(entry.entryType())));
        assertTrue(entries.stream().anyMatch(entry -> "artifact".equals(entry.entryType()) && entry.content().contains("debate output by")));
        assertTrue(entries.stream().anyMatch(entry -> "summary".equals(entry.entryType()) && entry.content().contains("debate summary")));
    }

    private String extractBoardId(String result) {
        String marker = "**Board ID**: ";
        int start = result.indexOf(marker);
        int end = result.indexOf('\n', start);
        return result.substring(start + marker.length(), end).trim();
    }
}
