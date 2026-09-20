package io.jobclaw.tools;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.ContextBuilder;
import io.jobclaw.agent.evolution.MemoryStore;
import io.jobclaw.agent.experience.*;
import io.jobclaw.agent.learning.LearningCandidateStore;
import io.jobclaw.config.Config;
import io.jobclaw.config.JobClawConfig;
import io.jobclaw.mcp.MCPService;
import io.jobclaw.session.SessionManager;
import io.jobclaw.skills.SkillsService;
import io.jobclaw.summary.SummaryService;
import io.jobclaw.workspace.WorkspaceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryWiringTest {
    @TempDir Path root;

    @Test
    void productionConstructorsShareOneStoreAndResolveInSpring() {
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(root.toString());
        var registry = mock(WorkspaceRegistry.class);
        new ApplicationContextRunner()
                .withBean(Config.class, () -> config)
                .withBean(MemoryStore.class, () -> new JobClawConfig().memoryStore(config, registry))
                .withBean(SessionManager.class, () -> mock(SessionManager.class))
                .withBean(SkillsService.class, () -> mock(SkillsService.class))
                .withBean(SummaryService.class, () -> mock(SummaryService.class))
                .withBean(MCPService.class, () -> mock(MCPService.class))
                .withBean(LearningCandidateStore.class, () -> mock(LearningCandidateStore.class))
                .withBean(ExperienceMemoryStore.class, () -> mock(ExperienceMemoryStore.class))
                .withUserConfiguration(ContextBuilder.class, MemoryTool.class,
                        ExperienceMemoryService.class, ExperienceMemoryRetriever.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    MemoryStore store = context.getBean(MemoryStore.class);
                    assertSame(store, context.getBean(ContextBuilder.class).getMemoryStore());
                    MemoryTool tool = context.getBean(MemoryTool.class);
                    tool.execute("remember", "PDF remembered", null, null, null, "project", .8);
                    assertTrue(store.getMemoryContext("PDF", 2048).contains("PDF remembered"));
                    store.addEntry("PDF summary", .7, List.of(), "session_summary");
                    assertTrue(tool.execute("search", null, "PDF summary", null, null, null, null).contains("PDF summary"));
                    assertEquals(2, new MemoryStore(root.toString()).getEntries().size());
                });
    }

    @Test
    void toolUpdatesOnlyAnExistingAccessibleIdAndNeverChangesItsScope() {
        MemoryStore store = new MemoryStore(root.toString());
        MemoryTool tool = new MemoryTool(store, null);
        setWorkspace("a");
        try {
            tool.execute("remember", "PDF old", null, null, null, null, .8);
            var entry = store.getEntries().getFirst();
            assertEquals("project", entry.getScope());
            assertTrue(tool.execute("update", "PDF new", null, entry.getId(), null, "user", null).contains("Updated memory"));
            assertEquals("project", store.getEntries().getFirst().getScope());
            assertEquals(1, store.getEntries().size());
            assertTrue(tool.execute("update", "not saved", null, null, null, null, null).startsWith("Error:"));
            assertTrue(tool.execute("update", "not saved", null, "missing", null, null, null).startsWith("Error:"));
            setWorkspace("b");
            assertFalse(tool.execute("list", null, null, null, null, null, null).contains("PDF new"));
            assertTrue(tool.execute("update", "stolen", null, entry.getId(), null, null, null).startsWith("Error:"));
            assertTrue(tool.execute("forget", null, null, entry.getId(), null, null, null).startsWith("Error:"));
        } finally {
            AgentExecutionContext.clear();
        }
    }

    private void setWorkspace(String name) {
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                name, null, null, null, null, null, null, null, root.resolve(name).toString()));
    }
}
