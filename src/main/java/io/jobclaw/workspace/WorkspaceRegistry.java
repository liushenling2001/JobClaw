package io.jobclaw.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jobclaw.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;

@Service
public class WorkspaceRegistry {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final Path storeFile;
    private final String fallbackPath;
    private final Map<String, WorkspaceRecord> workspaces = new LinkedHashMap<>();

    @Autowired
    public WorkspaceRegistry(Config config) {
        this(Path.of(System.getProperty("user.home"), ".jobclaw", "workspaces.json"),
                config.getWorkspacePath());
    }

    WorkspaceRegistry(Path storeFile, String fallbackPath) {
        this.storeFile = storeFile;
        this.fallbackPath = canonicalizeExistingOrAbsolute(fallbackPath);
        load();
        ensureDefaultWorkspace();
    }

    public synchronized List<WorkspaceRecord> list() {
        return workspaces.values().stream().map(this::copy).toList();
    }

    public synchronized Optional<WorkspaceRecord> find(String id) {
        return Optional.ofNullable(workspaces.get(id)).map(this::copy);
    }

    public synchronized WorkspaceRecord create(String path, String title) {
        String canonicalPath = requireDirectory(path);
        Optional<WorkspaceRecord> existing = workspaces.values().stream()
                .filter(workspace -> canonicalPath.equalsIgnoreCase(workspace.getPath()))
                .findFirst();
        if (existing.isPresent()) {
            return copy(existing.get());
        }

        Instant now = Instant.now();
        String resolvedTitle = normalizeTitle(title, Path.of(canonicalPath));
        WorkspaceRecord record = new WorkspaceRecord(
                "ws-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                resolvedTitle,
                canonicalPath,
                List.of(),
                now,
                now
        );
        workspaces.put(record.getId(), record);
        save();
        return copy(record);
    }

    public synchronized WorkspaceRecord rename(String id, String title) {
        WorkspaceRecord workspace = requireWorkspace(id);
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("workspace title is required");
        }
        workspace.setTitle(title.trim());
        workspace.setUpdatedAt(Instant.now());
        save();
        return copy(workspace);
    }

    public synchronized void remove(String id) {
        if (workspaces.remove(id) == null) {
            throw new IllegalArgumentException("workspace not found: " + id);
        }
        save();
    }

    public synchronized WorkspaceRecord attachSession(String id, String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            throw new IllegalArgumentException("sessionKey is required");
        }
        WorkspaceRecord workspace = requireWorkspace(id);
        for (WorkspaceRecord candidate : workspaces.values()) {
            candidate.getSessionKeys().remove(sessionKey);
        }
        if (!workspace.getSessionKeys().contains(sessionKey)) {
            workspace.getSessionKeys().add(0, sessionKey);
        }
        workspace.setUpdatedAt(Instant.now());
        save();
        return copy(workspace);
    }

    public synchronized void detachSession(String sessionKey) {
        boolean changed = false;
        for (WorkspaceRecord workspace : workspaces.values()) {
            changed |= workspace.getSessionKeys().remove(sessionKey);
        }
        if (changed) {
            save();
        }
    }

    public synchronized void removeNonWebSessionMemberships() {
        boolean changed = false;
        for (WorkspaceRecord workspace : workspaces.values()) {
            changed |= workspace.getSessionKeys().removeIf(sessionKey -> !isWebSession(sessionKey));
        }
        if (changed) {
            save();
        }
    }

    public synchronized void attachLegacySessionsToFallback(Collection<String> sessionKeys) {
        if (sessionKeys == null || sessionKeys.isEmpty()) {
            return;
        }
        WorkspaceRecord fallback = workspaces.values().stream()
                .filter(workspace -> fallbackPath.equalsIgnoreCase(workspace.getPath()))
                .findFirst()
                .orElse(null);
        if (fallback == null) {
            return;
        }
        boolean changed = false;
        for (String sessionKey : sessionKeys) {
            if (!isWebSession(sessionKey) || findBySession(sessionKey).isPresent()) {
                continue;
            }
            fallback.getSessionKeys().add(sessionKey);
            changed = true;
        }
        if (changed) {
            fallback.setUpdatedAt(Instant.now());
            save();
        }
    }

    private boolean isWebSession(String sessionKey) {
        return sessionKey != null && sessionKey.startsWith("web:");
    }

    public synchronized Optional<WorkspaceRecord> findBySession(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return Optional.empty();
        }
        return workspaces.values().stream()
                .filter(workspace -> workspace.getSessionKeys().contains(sessionKey))
                .findFirst()
                .map(this::copy);
    }

    public String resolveWorkingDirectory(String sessionKey) {
        return findBySession(sessionKey).map(WorkspaceRecord::getPath).orElse(fallbackPath);
    }

    public String getFallbackPath() {
        return fallbackPath;
    }

    private void ensureDefaultWorkspace() {
        if (fallbackPath == null || fallbackPath.isBlank() || !workspaces.isEmpty()) {
            return;
        }
        try {
            Path path = Path.of(fallbackPath);
            Files.createDirectories(path);
            create(path.toString(), "默认工作区");
        } catch (Exception e) {
            logger.warn("Failed to register default workspace {}: {}", fallbackPath, e.getMessage());
        }
    }

    private WorkspaceRecord requireWorkspace(String id) {
        WorkspaceRecord workspace = workspaces.get(id);
        if (workspace == null) {
            throw new IllegalArgumentException("workspace not found: " + id);
        }
        return workspace;
    }

    private String requireDirectory(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("workspace path is required");
        }
        try {
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException("workspace path is not an existing directory: " + path);
            }
            return path.toRealPath().toString();
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot resolve workspace path: " + value, e);
        }
    }

    private String canonicalizeExistingOrAbsolute(String value) {
        if (value == null || value.isBlank()) {
            return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString();
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        try {
            return Files.exists(path) ? path.toRealPath().toString() : path.toString();
        } catch (IOException e) {
            return path.toString();
        }
    }

    private String normalizeTitle(String title, Path path) {
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : path.toString();
    }

    private void load() {
        if (!Files.exists(storeFile)) {
            return;
        }
        try {
            List<WorkspaceRecord> loaded = MAPPER.readValue(
                    Files.readString(storeFile), new TypeReference<List<WorkspaceRecord>>() { });
            for (WorkspaceRecord workspace : loaded) {
                if (workspace.getId() != null && workspace.getPath() != null) {
                    workspace.setSessionKeys(workspace.getSessionKeys());
                    workspaces.put(workspace.getId(), workspace);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load workspace registry {}: {}", storeFile, e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(storeFile.getParent());
            Path temporary = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), new ArrayList<>(workspaces.values()));
            try {
                Files.move(temporary, storeFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, storeFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to save workspace registry: " + e.getMessage(), e);
        }
    }

    private WorkspaceRecord copy(WorkspaceRecord source) {
        return new WorkspaceRecord(source.getId(), source.getTitle(), source.getPath(), source.getSessionKeys(),
                source.getCreatedAt(), source.getUpdatedAt());
    }
}
