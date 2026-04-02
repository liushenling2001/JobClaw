package io.jobclaw.agent;

import io.jobclaw.board.BoardEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoardEventSummarizerTest {

    @Test
    void shouldAggregateBoardProgressCounters() {
        BoardEventSummarizer summarizer = new BoardEventSummarizer();
        String boardId = "board-xyz";

        ExecutionEvent e1 = summarizer.toProgressEvent("session-a", new BoardEntry(
                "entry-1", boardId, "artifact", "Step 1", "content", "coder", "Coder", "team", Instant.now()
        ));
        ExecutionEvent e2 = summarizer.toProgressEvent("session-a", new BoardEntry(
                "entry-2", boardId, "risk", "Risk 1", "content", "reviewer", "Reviewer", "team", Instant.now()
        ));

        assertEquals(1, e1.getMetadata().get("boardArtifactCount"));
        assertEquals(0, e1.getMetadata().get("boardRiskCount"));
        assertEquals(2, e2.getMetadata().get("boardTotalEntries"));
        assertEquals(1, e2.getMetadata().get("boardArtifactCount"));
        assertEquals(1, e2.getMetadata().get("boardRiskCount"));
        assertEquals("Risk 1", e2.getMetadata().get("latestEntryTitle"));
    }
}
