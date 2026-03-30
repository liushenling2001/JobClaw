package io.jobclaw.conversation.file;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jobclaw.conversation.ConversationStore;
import io.jobclaw.conversation.MessageChunk;
import io.jobclaw.conversation.SessionRecord;
import io.jobclaw.conversation.StoredMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class FileConversationStore implements ConversationStore {

    private static final Logger logger = LoggerFactory.getLogger(FileConversationStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final Path rootDir;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public FileConversationStore(String rootPath) {
        this.rootDir = rootPath == null || rootPath.isBlank()
                ? Paths.get(System.getProperty("user.home"), ".jobclaw", "conversation-store")
                : Paths.get(rootPath);
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            logger.warn("Failed to initialize conversation store directory: {}", e.getMessage());
        }
    }

    @Override
    public void appendMessage(StoredMessage message) {
        if (message == null || message.sessionId() == null || message.sessionId().isBlank()) {
            return;
        }

        synchronized (lockFor(message.sessionId())) {
            try {
                SessionRecord record = loadOrCreateSessionRecord(message.sessionId());
                List<StoredMessage> existing = readMessages(message.sessionId());
                long nextSeq = existing.isEmpty() ? 1 : existing.get(existing.size() - 1).sequence() + 1;

                StoredMessage normalized = new StoredMessage(
                        message.messageId() != null ? message.messageId() : message.sessionId() + "-" + nextSeq,
                        message.sessionId(),
                        message.sequence() > 0 ? message.sequence() : nextSeq,
                        message.role(),
                        message.content(),
                        message.toolName(),
                        message.toolCallId(),
                        message.toolArgsJson(),
                        message.toolResultJson(),
                        message.metadata() != null ? message.metadata() : Map.of(),
                        message.createdAt() != null ? message.createdAt() : Instant.now()
                );

                Path sessionDir = sessionDir(message.sessionId());
                Files.createDirectories(sessionDir);
                Path logFile = sessionDir.resolve("messages.ndjson");
                Files.writeString(
                        logFile,
                        MAPPER.writeValueAsString(normalized) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        Files.exists(logFile) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE
                );

                writeSessionRecord(record.withUpdatedAt(Instant.now())
                        .withLastMessageAt(normalized.createdAt())
                        .withMessageCount(nextSeq));
            } catch (Exception e) {
                logger.warn("Failed to append message for session {}: {}", message.sessionId(), e.getMessage());
            }
        }
    }

    @Override
    public List<StoredMessage> listRecentMessages(String sessionId, int limit) {
        List<StoredMessage> messages = readMessages(sessionId);
        if (messages.isEmpty() || limit <= 0) {
            return List.of();
        }
        int fromIndex = Math.max(0, messages.size() - limit);
        return new ArrayList<>(messages.subList(fromIndex, messages.size()));
    }

    @Override
    public List<StoredMessage> listMessages(String sessionId, int offset, int limit) {
        List<StoredMessage> messages = readMessages(sessionId);
        if (messages.isEmpty() || limit <= 0 || offset >= messages.size()) {
            return List.of();
        }
        int fromIndex = Math.max(0, offset);
        int toIndex = Math.min(messages.size(), fromIndex + limit);
        return new ArrayList<>(messages.subList(fromIndex, toIndex));
    }

    @Override
    public Optional<SessionRecord> getSession(String sessionId) {
        Path recordFile = sessionDir(sessionId).resolve("session.json");
        if (!Files.exists(recordFile)) {
            return Optional.empty();
        }
        try {
            SessionRecord record = MAPPER.readValue(Files.readString(recordFile), SessionRecord.class);
            return Optional.of(enrichSessionRecord(record, true));
        } catch (Exception e) {
            logger.warn("Failed to read session record for {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<SessionRecord> listSessions() {
        if (!Files.exists(rootDir)) {
            return List.of();
        }

        List<SessionRecord> sessions = new ArrayList<>();
        try (var stream = Files.list(rootDir)) {
            stream
                    .filter(Files::isDirectory)
                    .map(dir -> dir.resolve("session.json"))
                    .filter(Files::exists)
                    .forEach(path -> {
                        try {
                            sessions.add(enrichSessionRecord(MAPPER.readValue(Files.readString(path), SessionRecord.class), true));
                        } catch (Exception e) {
                            logger.debug("Failed to read session record from {}: {}", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            logger.warn("Failed to list sessions: {}", e.getMessage());
        }

        sessions.sort(Comparator.comparing(SessionRecord::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return sessions;
    }

    public void deleteSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        synchronized (lockFor(sessionId)) {
            Path sessionDir = sessionDir(sessionId);
            if (!Files.exists(sessionDir)) {
                return;
            }

            try (var walk = Files.walk(sessionDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        logger.debug("Failed to delete conversation path {}: {}", path, e.getMessage());
                    }
                });
            } catch (IOException e) {
                logger.warn("Failed to delete session {} from conversation store: {}", sessionId, e.getMessage());
            }
        }
    }

    @Override
    public void saveChunk(MessageChunk chunk) {
        if (chunk == null || chunk.sessionId() == null || chunk.sessionId().isBlank()) {
            return;
        }

        synchronized (lockFor(chunk.sessionId())) {
            try {
                Path sessionDir = sessionDir(chunk.sessionId());
                Files.createDirectories(sessionDir);
                Path chunkFile = sessionDir.resolve("chunks.ndjson");
                Files.writeString(
                        chunkFile,
                        MAPPER.writeValueAsString(chunk) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        Files.exists(chunkFile) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE
                );
            } catch (Exception e) {
                logger.warn("Failed to save chunk for session {}: {}", chunk.sessionId(), e.getMessage());
            }
        }
    }

    @Override
    public List<MessageChunk> listChunks(String sessionId) {
        Path chunkFile = sessionDir(sessionId).resolve("chunks.ndjson");
        if (!Files.exists(chunkFile)) {
            return List.of();
        }

        List<MessageChunk> chunks = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(chunkFile, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                chunks.add(MAPPER.readValue(line, MessageChunk.class));
            }
        } catch (Exception e) {
            logger.warn("Failed to read chunks for session {}: {}", sessionId, e.getMessage());
        }
        return chunks;
    }

    private List<StoredMessage> readMessages(String sessionId) {
        Path logFile = sessionDir(sessionId).resolve("messages.ndjson");
        if (!Files.exists(logFile)) {
            return List.of();
        }

        List<StoredMessage> messages = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                messages.add(MAPPER.readValue(line, StoredMessage.class));
            }
        } catch (Exception e) {
            logger.warn("Failed to read messages for session {}: {}", sessionId, e.getMessage());
        }
        return messages;
    }

    private SessionRecord loadOrCreateSessionRecord(String sessionId) {
        return getSession(sessionId).orElseGet(() -> new SessionRecord(
                sessionId,
                sessionId,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                0,
                "active",
                List.of()
        ));
    }

    private SessionRecord enrichSessionRecord(SessionRecord record, boolean persistIfChanged) {
        if (record == null) {
            return null;
        }
        if (record.getMessageCount() > 0) {
            return record;
        }

        List<StoredMessage> messages = readMessages(record.getSessionId());
        if (messages.isEmpty()) {
            return record;
        }

        Instant createdAt = record.getCreatedAt() != null
                ? record.getCreatedAt()
                : messages.get(0).createdAt();
        Instant lastMessageAt = record.getLastMessageAt() != null
                ? record.getLastMessageAt()
                : messages.get(messages.size() - 1).createdAt();
        Instant updatedAt = record.getUpdatedAt() != null ? record.getUpdatedAt() : lastMessageAt;

        SessionRecord enriched = new SessionRecord(
                record.getSessionId(),
                record.getTitle(),
                createdAt,
                updatedAt,
                lastMessageAt,
                messages.size(),
                record.getStatus(),
                record.getTags()
        );
        if (persistIfChanged) {
            writeSessionRecord(enriched);
        }
        return enriched;
    }

    private void writeSessionRecord(SessionRecord record) {
        try {
            Path sessionDir = sessionDir(record.getSessionId());
            Files.createDirectories(sessionDir);
            Files.writeString(
                    sessionDir.resolve("session.json"),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(record),
                    StandardCharsets.UTF_8
            );
        } catch (Exception e) {
            logger.warn("Failed to write session record for {}: {}", record.getSessionId(), e.getMessage());
        }
    }

    private Path sessionDir(String sessionId) {
        return rootDir.resolve(safeName(sessionId));
    }

    private Object lockFor(String sessionId) {
        return locks.computeIfAbsent(sessionId, key -> new Object());
    }

    private String safeName(String value) {
        return value.replaceAll("[:/\\\\*?\"<>|]", "_");
    }
}
