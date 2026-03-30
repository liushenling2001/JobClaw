package io.jobclaw.tools;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.BoardEventSummarizer;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.board.SharedBoardService;
import io.jobclaw.board.file.FileSharedBoardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedBoardToolTest {

    @AfterEach
    void tearDown() {
        AgentExecutionContext.clear();
    }

    @Test
    void shouldWriteAndReadSharedBoardEntries() throws Exception {
        Path tempDir = Files.createTempDirectory("shared-board-tool");
        SharedBoardService sharedBoardService = new FileSharedBoardService(tempDir.resolve("boards").toString());
        ExecutionTraceService traceService = new ExecutionTraceService();
        SharedBoardTool tool = new SharedBoardTool(sharedBoardService, traceService, new BoardEventSummarizer());
        String boardId = sharedBoardService.createBoard("run-test", "Test board").boardId();

        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "web:test",
                null,
                "run-test",
                null,
                "coder",
                "Coder"
        ));

        String writeResult = tool.boardWrite(boardId, "artifact", "Patch", "Implemented the fix", "team");
        String readResult = tool.boardRead(boardId, 10);

        assertTrue(writeResult.contains("Board entry written"));
        assertTrue(readResult.contains("[artifact] Patch"));
        assertTrue(readResult.contains("author=Coder"));
        assertTrue(readResult.contains("Implemented the fix"));
        assertTrue(traceService.getHistory("web:test").stream().anyMatch(event ->
                event.getMetadata().containsKey("boardId")
                        && boardId.equals(event.getMetadata().get("boardId"))
        ));
    }
}
