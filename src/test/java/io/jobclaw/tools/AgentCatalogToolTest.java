package io.jobclaw.tools;

import io.jobclaw.agent.catalog.AgentCatalogService;
import io.jobclaw.agent.catalog.SqliteAgentCatalogStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCatalogToolTest {

    @Test
    void shouldCreateAndListAgents() throws Exception {
        Path tempDir = Files.createTempDirectory("agent-catalog-tool");
        AgentCatalogTool tool = new AgentCatalogTool(
                new AgentCatalogService(new SqliteAgentCatalogStore(tempDir.resolve("agents.db").toString()))
        );

        String createResult = tool.agentCatalog(
                "create",
                "jd analyst",
                "Analyze job descriptions",
                null,
                "jd analyst,jd helper",
                "read_file,list_dir"
        );
        String listResult = tool.agentCatalog("list", null, null, null, null, null);

        assertTrue(createResult.contains("jd analyst"));
        assertTrue(listResult.contains("jd analyst"));
        assertTrue(listResult.contains("Analyze job descriptions"));
    }

    @Test
    void shouldUpdateDisableActivateAndDeleteAgent() throws Exception {
        Path tempDir = Files.createTempDirectory("agent-catalog-tool-mutate");
        AgentCatalogTool tool = new AgentCatalogTool(
                new AgentCatalogService(new SqliteAgentCatalogStore(tempDir.resolve("agents.db").toString()))
        );

        tool.agentCatalog(
                "create",
                "research helper",
                "Investigate companies",
                null,
                "research helper",
                "read_file,list_dir"
        );

        String updateResult = tool.agentCatalog(
                "update",
                "research helper",
                "Investigate companies and write short summaries",
                null,
                null,
                "read_file,list_dir,write_file"
        );
        String disableResult = tool.agentCatalog("disable", "research helper", null, null, null, null);
        String activateResult = tool.agentCatalog("activate", "research helper", null, null, null, null);
        String getResult = tool.agentCatalog("get", "research helper", null, null, null, null);
        String deleteResult = tool.agentCatalog("delete", "research helper", null, null, null, null);

        assertTrue(updateResult.contains("Agent updated"));
        assertTrue(disableResult.contains("Agent disabled"));
        assertTrue(activateResult.contains("Agent activated"));
        assertTrue(getResult.contains("write_file"));
        assertTrue(deleteResult.contains("Agent deleted"));
    }
}
