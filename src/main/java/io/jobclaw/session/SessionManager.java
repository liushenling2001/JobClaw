package io.jobclaw.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jobclaw.conversation.ConversationStore;
import io.jobclaw.conversation.MessageChunk;
import io.jobclaw.conversation.SessionRecord;
import io.jobclaw.conversation.StoredMessage;
import io.jobclaw.conversation.file.FileConversationStore;
import io.jobclaw.providers.Message;
import io.jobclaw.providers.ToolCall;
import io.jobclaw.summary.SummaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器 - 处理对话持久化
 */
@Component
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final Map<String, Session> sessions;
    private final String storagePath;
    private final ConversationStore conversationStore;
    private final SummaryService summaryService;

    public SessionManager() {
        this(
                Paths.get(System.getProperty("user.home"), ".jobclaw", "workspace", "sessions").toString(),
                new FileConversationStore(Paths.get(System.getProperty("user.home"), ".jobclaw", "workspace", "sessions", "conversation").toString()),
                null
        );
    }

    public SessionManager(String storagePath) {
        this(storagePath, new FileConversationStore(Paths.get(storagePath, "conversation").toString()), null);
    }

    public SessionManager(String storagePath, ConversationStore conversationStore) {
        this(storagePath, conversationStore, null);
    }

    public SessionManager(String storagePath, ConversationStore conversationStore, SummaryService summaryService) {
        this.sessions = new ConcurrentHashMap<>();
        this.storagePath = storagePath;
        this.conversationStore = conversationStore;
        this.summaryService = summaryService;
        initializeStorage();
    }

    private void initializeStorage() {
        if (storagePath != null && !storagePath.isEmpty()) {
            try {
                Files.createDirectories(Paths.get(storagePath));
                cleanupLegacySessionSnapshots();
            } catch (IOException e) {
                logger.warn("Failed to create session storage directory");
            }
        }
    }

    public Session getOrCreate(String key) {
        return sessions.computeIfAbsent(key, k -> rebuildSession(k).orElseGet(() -> {
            Session session = new Session(k);
            logger.debug("Created new session: {}", key);
            return session;
        }));
    }

    public void addMessage(String sessionKey, String role, String content) {
        Session session = getOrCreate(sessionKey);
        session.addMessage(role, content);
        conversationStore.appendMessage(toStoredMessage(sessionKey, session.getMessages().size(), role, content, null));
    }

    public void addFullMessage(String sessionKey, Message message) {
        Session session = getOrCreate(sessionKey);
        session.addFullMessage(message);
        conversationStore.appendMessage(toStoredMessage(sessionKey, session.getMessages().size(), message.getRole(),
                message.getContent(), message));
    }

    public List<Message> getHistory(String sessionKey) {
        Session session = getSession(sessionKey);
        return session != null ? session.getHistory() : List.of();
    }

    public String getSummary(String sessionKey) {
        if (summaryService != null) {
            String persisted = summaryService.getSessionSummary(sessionKey)
                    .map(io.jobclaw.summary.SessionSummaryRecord::summaryText)
                    .orElse("");
            if (!persisted.isBlank()) {
                return persisted;
            }
        }
        Session session = sessions.get(sessionKey);
        return session != null ? session.getSummary() : "";
    }

    public void setSummary(String sessionKey, String summary) {
        Session session = getOrCreate(sessionKey);
        session.setSummary(summary);
        session.setUpdated(Instant.now());
    }

    public void truncateHistory(String sessionKey, int keepLast) {
        logger.debug("truncateHistory is deprecated for append-only sessions, sessionKey={}, keepLast={}",
                sessionKey, keepLast);
    }

    public void save(Session session) {
        // Legacy session.json snapshots have been retired.
    }

    public Set<String> getSessionKeys() {
        Set<String> keys = new LinkedHashSet<>(sessions.keySet());
        for (SessionRecord record : conversationStore.listSessions()) {
            keys.add(record.getSessionId());
        }
        return keys;
    }

    public void deleteSession(String key) {
        sessions.remove(key);
        if (storagePath != null) {
            conversationStore.deleteSession(key);
            logger.debug("Deleted session: {}", key);
        }
    }

    public Session getSession(String key) {
        Session rebuilt = rebuildSession(key).orElse(sessions.get(key));
        if (rebuilt != null) {
            sessions.put(key, rebuilt);
        }
        return rebuilt;
    }

    public int getSessionCount() {
        return getSessionKeys().size();
    }

    public List<SessionRecord> listSessionRecords() {
        return conversationStore.listSessions();
    }

    public int getUserSessionCount() {
        return listUserSessionRecords().size();
    }

    public List<SessionRecord> listUserSessionRecords() {
        return conversationStore.listSessions().stream()
                .filter(record -> !isInternalSession(record.getSessionId()))
                .toList();
    }

    public void saveChunk(MessageChunk chunk) {
        conversationStore.saveChunk(chunk);
    }

    private String toSafeFileName(String key) {
        if (key == null) {
            return "unknown";
        }
        return key.replaceAll("[:/\\\\*?\"<>|]", "_");
    }

    private boolean isInternalSession(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return key.startsWith("spawn-") || key.startsWith("subagent-");
    }

    private Optional<Session> rebuildSession(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }

        List<StoredMessage> storedMessages = conversationStore.listMessages(key, 0, Integer.MAX_VALUE);
        Optional<SessionRecord> record = conversationStore.getSession(key);
        Session cached = sessions.get(key);
        if (storedMessages.isEmpty() && record.isEmpty() && cached == null) {
            return Optional.empty();
        }

        Session rebuilt = new Session(key);
        rebuilt.setMessages(restoreMessages(storedMessages));

        Instant createdAt = record.map(SessionRecord::getCreatedAt)
                .orElseGet(() -> firstMessageTime(storedMessages).orElseGet(() ->
                        cached != null ? cached.getCreated() : Instant.now()));
        Instant updatedAt = record.map(SessionRecord::getUpdatedAt)
                .orElseGet(() -> lastMessageTime(storedMessages).orElseGet(() ->
                        cached != null ? cached.getUpdated() : createdAt));
        rebuilt.setCreated(createdAt);
        rebuilt.setUpdated(updatedAt);
        String summary = getSummary(key);
        if (summary != null && !summary.isBlank()) {
            rebuilt.setSummary(summary);
        }

        return Optional.of(rebuilt);
    }

    private void cleanupLegacySessionSnapshots() {
        try (var stream = Files.list(Paths.get(storagePath))) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            logger.debug("Failed to delete legacy session snapshot {}: {}", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            logger.debug("Failed to scan legacy session snapshots: {}", e.getMessage());
        }
    }

    private List<Message> restoreMessages(List<StoredMessage> storedMessages) {
        List<Message> restored = new ArrayList<>();
        for (StoredMessage storedMessage : storedMessages) {
            Message message = new Message(storedMessage.role(), storedMessage.content());
            if (storedMessage.toolCallId() != null && !storedMessage.toolCallId().isBlank()) {
                message.setToolCallId(storedMessage.toolCallId());
            }
            if (storedMessage.metadata() != null && !storedMessage.metadata().isEmpty()) {
                Object images = storedMessage.metadata().get("images");
                if (images != null) {
                    message.setImages(objectMapper.convertValue(images, new TypeReference<List<String>>() {}));
                }
                Object toolCalls = storedMessage.metadata().get("toolCalls");
                if (toolCalls != null) {
                    message.setToolCalls(objectMapper.convertValue(toolCalls, new TypeReference<List<ToolCall>>() {}));
                }
            }
            restored.add(message);
        }
        return restored;
    }

    private Optional<Instant> firstMessageTime(List<StoredMessage> messages) {
        return messages.stream()
                .map(StoredMessage::createdAt)
                .filter(Objects::nonNull)
                .findFirst();
    }

    private Optional<Instant> lastMessageTime(List<StoredMessage> messages) {
        if (messages.isEmpty()) {
            return Optional.empty();
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Instant createdAt = messages.get(i).createdAt();
            if (createdAt != null) {
                return Optional.of(createdAt);
            }
        }
        return Optional.empty();
    }

    private StoredMessage toStoredMessage(String sessionKey, long sequence, String role, String content, Message message) {
        Map<String, Object> metadata = new HashMap<>();
        if (message != null) {
            if (message.getImages() != null && !message.getImages().isEmpty()) {
                metadata.put("images", message.getImages());
            }
            if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
                metadata.put("toolCalls", message.getToolCalls());
            }
            if (message.getToolCallId() != null) {
                metadata.put("toolCallId", message.getToolCallId());
            }
        }

        return new StoredMessage(
                sessionKey + "-" + sequence,
                sessionKey,
                sequence,
                role,
                content,
                null,
                message != null ? message.getToolCallId() : null,
                null,
                null,
                metadata,
                Instant.now()
        );
    }
}
