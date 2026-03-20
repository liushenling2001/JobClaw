package io.jobclaw.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jobclaw.providers.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
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

    public SessionManager() {
        this.sessions = new ConcurrentHashMap<>();
        this.storagePath = Paths.get(System.getProperty("user.home"), ".jobclaw", "workspace", "sessions").toString();
        initializeStorage();
    }

    public SessionManager(String storagePath) {
        this.sessions = new ConcurrentHashMap<>();
        this.storagePath = storagePath;
        initializeStorage();
    }

    private void initializeStorage() {
        if (storagePath != null && !storagePath.isEmpty()) {
            try {
                Files.createDirectories(Paths.get(storagePath));
                loadSessions();
            } catch (IOException e) {
                logger.warn("Failed to create session storage directory");
            }
        }
    }

    public Session getOrCreate(String key) {
        return sessions.computeIfAbsent(key, k -> {
            Session session = new Session(k);
            logger.debug("Created new session: {}", key);
            return session;
        });
    }

    public void addMessage(String sessionKey, String role, String content) {
        Session session = getOrCreate(sessionKey);
        session.addMessage(role, content);
    }

    public void addFullMessage(String sessionKey, Message message) {
        Session session = getOrCreate(sessionKey);
        session.addFullMessage(message);
    }

    public List<Message> getHistory(String sessionKey) {
        Session session = sessions.get(sessionKey);
        return session != null ? session.getHistory() : List.of();
    }

    public String getSummary(String sessionKey) {
        Session session = sessions.get(sessionKey);
        return session != null ? session.getSummary() : "";
    }

    public void setSummary(String sessionKey, String summary) {
        Session session = sessions.get(sessionKey);
        if (session != null) {
            session.setSummary(summary);
            session.setUpdated(Instant.now());
        }
    }

    public void truncateHistory(String sessionKey, int keepLast) {
        Session session = sessions.get(sessionKey);
        if (session != null) {
            session.truncateHistory(keepLast);
        }
    }

    public void save(Session session) {
        if (storagePath == null || storagePath.isEmpty()) {
            return;
        }

        try {
            String safeFileName = toSafeFileName(session.getKey());
            String sessionFile = Paths.get(storagePath, safeFileName + ".json").toString();
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(session);
            Files.writeString(Paths.get(sessionFile), json);
            logger.debug("Saved session: {}", session.getKey());
        } catch (IOException e) {
            logger.error("Failed to save session: {}", session.getKey(), e);
        }
    }

    private void loadSessions() {
        if (storagePath == null) {
            return;
        }

        File storageDir = new File(storagePath);
        if (!storageDir.exists() || !storageDir.isDirectory()) {
            return;
        }

        File[] files = storageDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            try {
                String content = Files.readString(file.toPath());
                Session session = objectMapper.readValue(content, Session.class);
                sessions.put(session.getKey(), session);
                logger.debug("Loaded session: {}", session.getKey());
            } catch (IOException e) {
                logger.warn("Failed to load session from file: {}", file.getName());
            }
        }

        logger.info("Loaded {} sessions from storage", sessions.size());
    }

    public Set<String> getSessionKeys() {
        return sessions.keySet();
    }

    public void deleteSession(String key) {
        Session removed = sessions.remove(key);
        if (removed != null && storagePath != null) {
            try {
                String safeFileName = toSafeFileName(key);
                Files.deleteIfExists(Paths.get(storagePath, safeFileName + ".json"));
                logger.debug("Deleted session: {}", key);
            } catch (IOException e) {
                logger.warn("Failed to delete session file: {}", key);
            }
        }
    }

    public Session getSession(String key) {
        return sessions.get(key);
    }

    public int getSessionCount() {
        return sessions.size();
    }

    private String toSafeFileName(String key) {
        if (key == null) {
            return "unknown";
        }
        return key.replaceAll("[:/\\\\*?\"<>|]", "_");
    }
}
