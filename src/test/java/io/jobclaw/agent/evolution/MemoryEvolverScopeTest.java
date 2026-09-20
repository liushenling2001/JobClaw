package io.jobclaw.agent.evolution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MemoryEvolverScopeTest {
    @TempDir Path root;

    @Test
    void backgroundSummaryExtractionUsesCapturedScopeAndDeduplicatesWithinIt() {
        var store = new MemoryStore(root.toString());
        var evolver = new MemoryEvolver(store, null, "unused");
        var a = new MemoryScope(root.resolve("a").toString(), "writer");
        var b = new MemoryScope(root.resolve("b").toString(), "writer");
        String summary = "用户喜欢使用 PDF 报告。";
        evolver.quickExtractFromSummary("child-a", summary, a);
        evolver.quickExtractFromSummary("child-a", summary, a);
        evolver.quickExtractFromSummary("child-b", summary, b);
        assertEquals(1, store.getEntries(a).size());
        assertEquals(1, store.getEntries(b).size());
        assertEquals(0, store.getEntries(store.defaultScope()).size());
        assertEquals("project", store.getEntries(a).getFirst().getScope());
        assertEquals(2, new MemoryStore(root.toString()).getEntries().size());
    }
}
