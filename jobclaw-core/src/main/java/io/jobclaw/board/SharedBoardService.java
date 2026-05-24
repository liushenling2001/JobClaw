package io.jobclaw.board;

import java.util.List;
import java.util.Optional;

public interface SharedBoardService {

    BoardRecord createBoard(String runId, String title);

    Optional<BoardRecord> getBoard(String boardId);

    BoardEntry writeEntry(String boardId,
                          String entryType,
                          String title,
                          String content,
                          String authorAgentId,
                          String authorAgentName,
                          String visibility);

    List<BoardEntry> readEntries(String boardId, int limit);
}
