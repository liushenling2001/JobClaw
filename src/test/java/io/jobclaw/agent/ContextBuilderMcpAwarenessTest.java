package io.jobclaw.agent;

import io.jobclaw.config.Config;
import io.jobclaw.config.MCPServersConfig;
import io.jobclaw.conversation.file.FileConversationStore;
import io.jobclaw.mcp.MCPService;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.SummaryService;
import io.jobclaw.summary.file.FileSummaryService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextBuilderMcpAwarenessTest {

    @Test
    void shouldExposeConfiguredMcpServersInSystemPrompt() throws Exception {
        Path workspace = Files.createTempDirectory("context-builder-mcp-awareness");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(workspace.toString());

        MCPServersConfig mcpServers = config.getMcpServers();
        mcpServers.setEnabled(true);
        MCPServersConfig.MCPServerConfig serverConfig = new MCPServersConfig.MCPServerConfig();
        serverConfig.setName("docs");
        serverConfig.setType("sse");
        serverConfig.setEnabled(true);
        serverConfig.setEndpoint("");
        mcpServers.getServers().add(serverConfig);

        String sessionsPath = workspace.resolve("sessions").toString();
        SummaryService summaryService = new FileSummaryService(workspace.resolve("sessions").resolve("conversation").toString());
        SessionManager sessionManager = new SessionManager(
                sessionsPath,
                new FileConversationStore(workspace.resolve("sessions").resolve("conversation").toString()),
                summaryService
        );
        MCPService mcpService = new MCPService();
        ContextBuilder builder = new ContextBuilder(config, sessionManager, null, summaryService, mcpService);

        String prompt = builder.buildSystemPrompt("web:test", "列出可用的MCP资源");
        MCPService.MCPServer snapshot = mcpService.getServer("docs");

        assertNotNull(snapshot);
        assertFalse(snapshot.isConnected());
        assertTrue(prompt.contains("# MCP"));
        assertTrue(prompt.contains("`docs`"));
        assertTrue(prompt.contains("disconnected"));
        assertTrue(prompt.contains("MCP endpoint is empty"));
        assertTrue(prompt.contains("mcp(action='list_servers')"));
    }
}
