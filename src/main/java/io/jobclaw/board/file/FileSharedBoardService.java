package io.jobclaw.board.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jobclaw.board.BoardEntry;
import io.jobclaw.board.BoardRecord;
import io.jobclaw.board.SharedBoardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FileSharedBoardService implements SharedBoardService {

    private static final Logger logger = LoggerFactory.getLogger(FileSharedBoardService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final Path basePath;
    private final ConcurrentHashMap<String, Object> boardLocks = new ConcurrentHashMap<>();

    public FileSharedBoardService(String basePath) {
        this.basePath = Paths.get(basePath);
        try {
            Files.createDirectories(this.basePath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize shared board directory: " + this.basePath, e);
        }
    }

    @Override
    public BoardRecord createBoard(String runId, String title) {
        String boardId = "board-" + UUID.randomUUID().toString().substring(0, 12);
        BoardRecord record = new BoardRecord(boardId, runId, title, Instant.now());
        synchronized (lockFor(boardId)) {
            try {
                Files.createDirectories(boardDir(boardId));
                Files.writeString(metadataFile(boardId), MAPPER.writeValueAsString(record), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create board " + boardId, e);
            }
        }
        return record;
    }

    @Override
    public Optional<BoardRecord> getBoard(String boardId) {
        if (boardId == null || boardId.isBlank()) {
            return Optional.empty();
        }
        Path file = metadataFile(boardId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(Files.readString(file), BoardRecord.class));
        } catch (IOException e) {
            logger.warn("Failed to read board metadata {}: {}", boardId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public BoardEntry writeEntry(String boardId,
                                 String entryType,
                                 String title,
                                 String content,
                                 String authorAgentId,
                                 String authorAgentName,
                                 String visibility) {
        requireBoard(boardId);
        BoardEntry entry = new BoardEntry(
                "entry-" + UUID.randomUUID().toString().substring(0, 12),
                boardId,
                safe(entryType, "note"),
                safe(title, "Untitled"),
                safe(content, ""),
                safe(authorAgentId, "system"),
                safe(authorAgentName, "System"),
                safe(visibility, "team"),
                Instant.now()
        );
        synchronized (lockFor(boardId)) {
            try {
                Files.writeString(
                        entriesFile(boardId),
                        MAPPER.writeValueAsString(entry) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException e) {
                throw new IllegalStateException("Failed to append board entry for " + boardId, e);
            }
        }
        return entry;
    }

    @Override
    public List<BoardEntry> readEntries(String boardId, int limit) {
        requireBoard(boardId);
        Path file = entriesFile(boardId);
        if (!Files.exists(file)) {
            return List.of();
        }
        int normalizedLimit = limit <= 0 ? Integer.MAX_VALUE : limit;
        List<BoardEntry> entries = new ArrayList<>();
        synchronized (lockFor(boardId)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    entries.add(MAPPER.readValue(line, BoardEntry.class));
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read board entries for " + boardId, e);
            }
        }
        int fromIndex = Math.max(0, entries.size() - normalizedLimit);
        return entries.subList(fromIndex, entries.size());
    }

    private void requireBoard(String boardId) {
        if (getBoard(boardId).isEmpty()) {
            throw new IllegalArgumentException("Shared board not found: " + boardId);
        }
    }

    private Object lockFor(String boardId) {
        return boardLocks.computeIfAbsent(boardId, ignored -> new Object());
    }

    private Path boardDir(String boardId) {
        return basePath.resolve(boardId);
    }

    private Path metadataFile(String boardId) {
        return boardDir(boardId).resolve("board.json");
    }

    private Path entriesFile(String boardId) {
        return boardDir(boardId).resolve("entries.jsonl");
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
