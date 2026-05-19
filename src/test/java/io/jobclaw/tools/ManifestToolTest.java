package io.jobclaw.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.skill.ActiveSkillRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestToolTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        AgentExecutionContext.clear();
    }

    @Test
    void createShouldBeIdempotentForSameTaskAndItems() throws Exception {
        AgentExecutionContext.setCurrentContext("session-a", event -> {});
        ManifestTool tool = new ManifestTool(tempDir);
        String items = """
                [
                  {"id":"pdf-001","title":"a.pdf"},
                  {"id":"pdf-002","title":"b.pdf"}
                ]
                """;

        String first = tool.manifest("create", null, "招生PDF分析", items,
                "{\"columns\":[\"title\",\"author\"]}", null, "D:/out/results.jsonl",
                null, null, null, null, null, null);
        String second = tool.manifest("create", null, "招生PDF分析", items,
                "{\"columns\":[\"title\",\"author\"]}", null, "D:/out/results.jsonl",
                null, null, null, null, null, null);

        String manifestId = extractManifestId(first);
        assertTrue(second.contains("Manifest already exists."));
        assertTrue(second.contains(manifestId));
        assertEquals(1, Files.list(tempDir.resolve("session-a")).count());
    }

    @Test
    void createShouldUpsertExistingManifestForSameExplicitTaskKey() throws Exception {
        AgentExecutionContext.setCurrentContext("session-task-key", event -> {});
        ManifestTool tool = new ManifestTool(tempDir);

        String first = tool.manifest("create", null, "skill:batch-document-extract-excel|inputDir=D:/docs",
                "[{\"id\":\"doc-001\",\"title\":\"A\"},{\"id\":\"doc-002\",\"title\":\"B\"}]",
                "{\"columns\":[\"title\"]}", null, "D:/docs/results.jsonl",
                null, null, null, null, null, null);
        String manifestId = extractManifestId(first);

        String second = tool.manifest("create", null, "skill:batch-document-extract-excel|inputDir=D:/docs",
                "[{\"id\":\"doc-002\",\"title\":\"B again\"},{\"id\":\"doc-003\",\"title\":\"C\"}]",
                "{\"columns\":[\"title\"]}", null, "D:/docs/results.jsonl",
                null, null, null, null, null, null);
        String status = tool.manifest("status", manifestId, null, null, null,
                null, null, null, null, null, "all", "10", null);

        assertTrue(second.contains("Manifest already exists."));
        assertTrue(second.contains(manifestId));
        assertTrue(second.contains("Items added: 1, duplicates skipped: 1"));
        assertEquals(1, Files.list(tempDir.resolve("session-task-key")).count());
        assertTrue(status.contains("total: 3"));
        assertTrue(status.contains("- doc-001 | pending | A"));
        assertTrue(status.contains("- doc-002 | pending | B"));
        assertTrue(status.contains("- doc-003 | pending | C"));
    }

    @Test
    void createShouldStoreManagedExecutionModeWhenExplicit() {
        AgentExecutionContext.setCurrentContext("session-managed", event -> {});
        ManifestTool tool = new ManifestTool(tempDir);

        String created = tool.manifest("create", null, "managed-batch",
                "[{\"id\":\"doc-001\",\"title\":\"A\"}]",
                "{\"columns\":[\"title\"]}", null, "D:/docs/results.jsonl",
                null, null, null, null, null, null, "managed");

        assertTrue(created.contains("executionMode: managed"));
        assertTrue(created.contains("executionMode=managed"));
    }

    @Test
    void createShouldStoreFinalArtifactExpectationWhenExplicit() {
        AgentExecutionContext.setCurrentContext("session-final-artifact", event -> {});
        ManifestTool tool = new ManifestTool(tempDir);

        String created = tool.manifest("create", null, "managed-batch",
                "[{\"id\":\"doc-001\",\"title\":\"A\"}]",
                "{\"columns\":[\"title\"]}", null, "D:/docs/results.jsonl",
                null, null, null, null, null, null, "managed", "D:/docs/results.xlsx", "xlsx");

        assertTrue(created.contains("finalArtifactPath: D:/docs/results.xlsx"));
        assertTrue(created.contains("finalArtifactType: xlsx"));
        assertTrue(created.contains("[not ready]"));
    }

    @Test
    void directCreateShouldNotRequireManagedSkillContract() {
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "session-direct-managed", event -> {}, "run-direct", null, null, null, null
        ));
        ManifestTool tool = new ManifestTool(tempDir);

        String created = tool.manifest("create", null, "managed-batch",
                "[{\"id\":\"doc-001\",\"title\":\"A\"}]",
                null, null, null,
                null, null, null, null, null, null, "managed");

        assertTrue(created.contains("Manifest ready."));
        assertTrue(created.contains("executionMode: managed"));
    }

    @Test
    void skillManagedCreateShouldRejectIncompleteRunnerContract() {
        ActiveSkillRegistry skillRegistry = new ActiveSkillRegistry();
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "session-skill-managed", event -> {}, "run-managed", null, null, null, null
        ));
        skillRegistry.activate("session-skill-managed", "run-managed", "batch", """
                # Skill
                ## Managed Runtime
                mode: runner
                ### Item Loop
                Process {{item.id}}.
                """, "E:\\skills\\batch");
        ManifestTool tool = new ManifestTool(tempDir, new io.jobclaw.agent.manifest.ActiveManifestRegistry(), skillRegistry);

        String created = tool.manifest("create", null, "managed-batch",
                "[{\"id\":\"doc-001\",\"title\":\"A\"}]",
                "{\"columns\":[\"title\"]}", null, "D:/docs/results.jsonl",
                null, null, null, null, null, null, "managed");

        assertTrue(created.contains("Error: managed manifest contract is incomplete"));
        assertTrue(created.contains("itemOutput"));
    }

    @Test
    void statusShouldFillMissingManagedFinalArtifactForExistingManifest() {
        AgentExecutionContext.setCurrentContext("session-status-final", event -> {});
        ManifestTool tool = new ManifestTool(tempDir);

        String created = tool.manifest("create", null, "batch",
                "[{\"id\":\"doc-001\",\"title\":\"A\"}]",
                "{\"columns\":[\"title\"]}", null, "D:/docs/results.jsonl",
                null, null, null, null, null, null);
        String manifestId = extractManifestId(created);

        String status = tool.manifest("status", manifestId, null, null, null,
                null, null, null, null, null, null, null, null,
                "managed", "D:/docs/results.xlsx", "xlsx");

        assertTrue(status.contains("executionMode: managed"));
        assertTrue(status.contains("finalArtifactPath: D:/docs/results.xlsx"));
        assertTrue(status.contains("finalArtifactType: xlsx"));
    }

    @Test
    void addItemsShouldRequireExplicitActionAndDeduplicateByItemId() {
        AgentExecutionContext.setCurrentContext("session-b", event -> {});
        ManifestTool tool = new ManifestTool(tempDir);

        String created = tool.manifest("create", null, "batch", "[{\"id\":\"a\",\"title\":\"A\"}]",
                null, null, null, null, null, null, null, null, null);
        String manifestId = extractManifestId(created);

        String added = tool.manifest("add_items", manifestId, null,
                "[{\"id\":\"a\",\"title\":\"A again\"},{\"id\":\"b\",\"title\":\"B\"}]",
                null, null, null, null, null, null, null, null, null);
        String status = tool.manifest("status", manifestId, null, null, null,
                null, null, null, null, null, "all", "10", null);

        assertTrue(added.contains("Items added: 1, duplicates skipped: 1"));
        assertTrue(status.contains("total: 2"));
        assertTrue(status.contains("- a | pending | A"));
        assertTrue(status.contains("- b | pending | B"));
    }

    @Test
    void createShouldRepairCommonMalformedJsonItemArray() {
        AgentExecutionContext.setCurrentContext("session-repair-items", event -> {});
        ManifestTool tool = new ManifestTool(tempDir);

        String created = tool.manifest("create", null, "batch",
                "[{\"id\":\"doc-01.txt\",\"title\":\"doc-01.txt\"} {\"id\":\"doc-02.txt\",\"title\":\"doc-02.txt\"}]",
                "{\"columns\":[\"title\"]}", null, "D:/docs/results.jsonl",
                null, null, null, null, null, null);

        assertTrue(created.contains("Manifest ready."));
        assertTrue(created.contains("total: 2"));
    }

    @Test
    void statusUpdatesShouldBeIdempotentAndCountsDerivedFromItems() {
        AgentExecutionContext.setCurrentContext("session-c", event -> {});
        ManifestTool tool = new ManifestTool(tempDir);

        String created = tool.manifest("create", null, "batch", "[\"A\",\"B\"]",
                null, null, null, null, null, null, null, null, null);
        String manifestId = extractManifestId(created);

        tool.manifest("start", manifestId, null, null, null,
                "item-001", null, null, "reading", null, null, null, null);
        tool.manifest("done", manifestId, null, null, null,
                "item-001", "D:/out/results.jsonl", "ref-1", "done", null, null, null, null);
        String repeatedDone = tool.manifest("done", manifestId, null, null, null,
                "item-001", "D:/out/results.jsonl", "ref-1", "done", null, null, null, null);
        String failed = tool.manifest("fail", manifestId, null, null, null,
                "item-002", null, null, null, "parse failed", null, null, null);

        assertTrue(repeatedDone.contains("done: 1"));
        assertTrue(failed.contains("failed: 1"));
        assertTrue(failed.contains("pending: 0"));
    }

    @Test
    void shouldHandleRealisticBatchDocumentAnalysisLedger() throws Exception {
        Path docs = tempDir.resolve("招生");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("2026-001-招生政策研究.pdf"), "title=招生政策研究");
        Files.writeString(docs.resolve("2026-002-课程改革成效.pdf"), "title=课程改革成效");
        Files.writeString(docs.resolve("2026-003-就业质量分析.pdf"), "title=就业质量分析");

        AgentExecutionContext.setCurrentContext("admission-session", event -> {});
        ManifestTool tool = new ManifestTool(tempDir.resolve("manifests"));
        String items = OBJECT_MAPPER.writeValueAsString(Files.list(docs)
                .sorted()
                .map(path -> Map.of(
                        "id", path.getFileName().toString().replace(".pdf", ""),
                        "title", path.toString()
                ))
                .toList());

        String created = tool.manifest("create", null, "招生PDF批量分析", items,
                "{\"columns\":[\"题目\",\"作者\",\"主要内容\"]}", null,
                docs.resolve("results.jsonl").toString(), null, null, null, null, null, null);
        String manifestId = extractManifestId(created);
        tool.manifest("start", manifestId, null, null, null,
                "2026-001-招生政策研究", null, null, "读取 PDF 文本", null, null, null, null);
        tool.manifest("done", manifestId, null, null, null,
                "2026-001-招生政策研究", docs.resolve("results.jsonl").toString(), "ref-policy",
                "已抽取题目、作者、主要内容", null, null, null, null);
        tool.manifest("fail", manifestId, null, null, null,
                "2026-002-课程改革成效", null, null, null, "PDF 扫描件无法读取", null, null, null);
        String status = tool.manifest("status", manifestId, null, null, null,
                null, null, null, null, null, "all", "10", null);

        assertTrue(status.contains("total: 3"));
        assertTrue(status.contains("pending: 1"));
        assertTrue(status.contains("done: 1"));
        assertTrue(status.contains("failed: 1"));
        assertTrue(status.contains("artifactPath: " + docs.resolve("results.jsonl")));
        assertTrue(status.contains("refId=ref-policy"));
    }

    private String extractManifestId(String result) {
        return result.lines()
                .filter(line -> line.startsWith("Manifest: "))
                .map(line -> line.substring("Manifest: ".length()).trim())
                .findFirst()
                .orElseThrow();
    }
}
