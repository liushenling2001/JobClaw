package io.jobclaw.tools;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.BoardEventSummarizer;
import io.jobclaw.agent.ExecutionEvent;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.board.BoardEntry;
import io.jobclaw.board.SharedBoardService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class SharedBoardTool {

    private final SharedBoardService sharedBoardService;
    private final ExecutionTraceService executionTraceService;
    private final BoardEventSummarizer boardEventSummarizer;

    public SharedBoardTool(SharedBoardService sharedBoardService,
                           ExecutionTraceService executionTraceService,
                           BoardEventSummarizer boardEventSummarizer) {
        this.sharedBoardService = sharedBoardService;
        this.executionTraceService = executionTraceService;
        this.boardEventSummarizer = boardEventSummarizer;
    }

    @Tool(name = "board_write", description = "Write a structured note, finding, artifact, or decision to a shared collaboration board.")
    public String boardWrite(
            @ToolParam(description = "Shared board ID") String boardId,
            @ToolParam(description = "Entry type, for example task, fact, artifact, decision, risk, question, or summary") String entryType,
            @ToolParam(description = "Short title for the entry") String title,
            @ToolParam(description = "Main content for the entry") String content,
            @ToolParam(description = "Visibility label, usually team or master_only") String visibility
    ) {
        if (boardId == null || boardId.isBlank()) {
            return "Error: boardId is required.";
        }
        if (content == null || content.isBlank()) {
            return "Error: content is required.";
        }
        AgentExecutionContext.ExecutionScope scope = AgentExecutionContext.getCurrentScope();
        String agentId = scope != null ? scope.agentId() : "system";
        String agentName = scope != null ? scope.agentName() : "System";
        BoardEntry entry = sharedBoardService.writeEntry(
                boardId.trim(),
                entryType,
                title,
                content,
                agentId,
                agentName,
                visibility
        );
        String eventSessionId = scope != null && scope.sessionKey() != null ? scope.sessionKey() : boardId.trim();
        ExecutionEvent event = boardEventSummarizer.toProgressEvent(eventSessionId, entry)
                .withMetadata("authorAgentId", entry.authorAgentId())
                .withMetadata("authorAgentName", entry.authorAgentName());
        executionTraceService.publish(event);
        return "Board entry written: `" + entry.entryId() + "` to `" + entry.boardId() + "`";
    }

    @Tool(name = "board_read", description = "Read recent entries from a shared collaboration board.")
    public String boardRead(
            @ToolParam(description = "Shared board ID") String boardId,
            @ToolParam(description = "Maximum number of recent entries to read. Default 20.") Integer limit
    ) {
        if (boardId == null || boardId.isBlank()) {
            return "Error: boardId is required.";
        }
        int normalizedLimit = limit != null ? limit : 20;
        List<BoardEntry> entries = sharedBoardService.readEntries(boardId.trim(), normalizedLimit);
        if (entries.isEmpty()) {
            return "Shared board `" + boardId.trim() + "` has no entries.";
        }
        return entries.stream()
                .map(entry -> "- [" + entry.entryType() + "] " + entry.title()
                        + " | author=" + entry.authorAgentName()
                        + "\n" + entry.content())
                .collect(Collectors.joining("\n\n"));
    }
}
