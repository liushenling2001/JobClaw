package io.jobclaw.agent.catalog;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteAgentCatalogStoreTest {

    @Test
    void shouldPersistAndResolveAgentByAlias() throws Exception {
        Path tempDir = Files.createTempDirectory("agent-catalog-test");
        SqliteAgentCatalogStore store = new SqliteAgentCatalogStore(tempDir.resolve("agents.db").toString());
        Instant now = Instant.now();

        AgentCatalogEntry entry = new AgentCatalogEntry(
                "agent-jd",
                "jd_analyst",
                "JD Analyst",
                "Analyze job descriptions",
                "You analyze JD text.",
                List.of("JD分析师", "岗位分析助手"),
                List.of("read_file"),
                List.of(),
                Map.of("model", "qwen"),
                "agent:jd_analyst",
                "active",
                "private",
                1,
                now,
                now
        );

        store.save(entry);

        assertTrue(store.findByCode("jd_analyst").isPresent());
        assertTrue(store.findByAlias("JD分析师").isPresent());
        assertEquals("JD Analyst", store.findByAlias("岗位分析助手").orElseThrow().displayName());
    }
}
