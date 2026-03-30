package io.jobclaw.agent;

import io.jobclaw.board.BoardEntry;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BoardEventSummarizer {

    private final ConcurrentHashMap<String, BoardProgress> progressByBoard = new ConcurrentHashMap<>();

    public ExecutionEvent toProgressEvent(String sessionId, BoardEntry entry) {
        BoardProgress progress = progressByBoard.compute(entry.boardId(), (boardId, current) -> {
            BoardProgress next = current != null ? current.copy() : new BoardProgress();
            next.totalEntries++;
            switch (entry.entryType()) {
                case "artifact" -> next.artifactCount++;
                case "risk" -> next.riskCount++;
                case "summary" -> next.summaryCount++;
                case "decision" -> next.decisionCount++;
                default -> {
                }
            }
            next.lastEntryType = entry.entryType();
            next.lastEntryTitle = entry.title();
            next.lastEntryId = entry.entryId();
            return next;
        });

        String content = "Board progress: total=" + progress.totalEntries
                + ", artifacts=" + progress.artifactCount
                + ", risks=" + progress.riskCount
                + ", summaries=" + progress.summaryCount
                + ", latest=[" + progress.lastEntryType + "] " + progress.lastEntryTitle;

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("boardId", entry.boardId());
        metadata.put("entryId", entry.entryId());
        metadata.put("entryType", entry.entryType());
        metadata.put("latestEntryType", progress.lastEntryType);
        metadata.put("latestEntryTitle", progress.lastEntryTitle);
        metadata.put("latestEntryId", progress.lastEntryId);
        metadata.put("boardTotalEntries", progress.totalEntries);
        metadata.put("boardArtifactCount", progress.artifactCount);
        metadata.put("boardRiskCount", progress.riskCount);
        metadata.put("boardSummaryCount", progress.summaryCount);
        metadata.put("boardDecisionCount", progress.decisionCount);

        return new ExecutionEvent(
                sessionId,
                ExecutionEvent.EventType.CUSTOM,
                content,
                metadata
        );
    }

    private static final class BoardProgress {
        private int totalEntries;
        private int artifactCount;
        private int riskCount;
        private int summaryCount;
        private int decisionCount;
        private String lastEntryType = "note";
        private String lastEntryTitle = "Untitled";
        private String lastEntryId = "";

        private BoardProgress copy() {
            BoardProgress copy = new BoardProgress();
            copy.totalEntries = this.totalEntries;
            copy.artifactCount = this.artifactCount;
            copy.riskCount = this.riskCount;
            copy.summaryCount = this.summaryCount;
            copy.decisionCount = this.decisionCount;
            copy.lastEntryType = this.lastEntryType;
            copy.lastEntryTitle = this.lastEntryTitle;
            copy.lastEntryId = this.lastEntryId;
            return copy;
        }
    }
}
