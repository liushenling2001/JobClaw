package io.jobclaw.context.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public class FileResultStore implements ResultStore {
    private static final Logger logger = LoggerFactory.getLogger(FileResultStore.class);
    private static final int DEFAULT_PREVIEW_CHARS = 2000;

    private final Path root;
    private final int previewChars;
    private final ObjectMapper objectMapper;

    public FileResultStore(Path root) {
        this(root, DEFAULT_PREVIEW_CHARS);
    }

    public FileResultStore(Path root, int previewChars) {
        this.root = root;
        this.previewChars = previewChars > 0 ? previewChars : DEFAULT_PREVIEW_CHARS;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize result store: " + root, e);
        }
    }

    @Override
    public ContextRef save(String sessionKey, String runId, String sourceType, String sourceName, String content) {
        String safeSession = safeSegment(sessionKey != null && !sessionKey.isBlank() ? sessionKey : "unknown-session");
        String safeRun = safeSegment(runId != null && !runId.isBlank() ? runId : "no-run");
        String refId = "ref-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String value = content != null ? content : "";
        StoredResult storedResult = new StoredResult(
                refId,
                sessionKey,
                runId,
                sourceType,
                sourceName,
                Instant.now(),
                value.length(),
                preview(value),
                value
        );
        Path file = root.resolve(safeSession).resolve(safeRun).resolve(refId + ".json");
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), storedResult);
            return storedResult.toRef();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store context result " + refId, e);
        }
    }

    @Override
    public Optional<StoredResult> find(String refId) {
        if (refId == null || refId.isBlank()) {
            return Optional.empty();
        }
        String fileName = safeSegment(refId.trim()) + ".json";
        try (Stream<Path> paths = Files.exists(root) ? Files.walk(root) : Stream.empty()) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .findFirst()
                    .flatMap(this::readResult);
        } catch (IOException e) {
            logger.warn("Failed to find context result {}: {}", refId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<ContextRef> list(String sessionKey, String runId, int limit) {
        int effectiveLimit = limit > 0 ? limit : 20;
        try (Stream<Path> paths = Files.exists(root) ? Files.walk(root) : Stream.empty()) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::readResult)
                    .flatMap(Optional::stream)
                    .filter(result -> matches(sessionKey, result.getSessionKey()))
                    .filter(result -> runId == null || runId.isBlank() || matches(runId, result.getRunId()))
                    .sorted(Comparator.comparing(StoredResult::getCreatedAt).reversed())
                    .limit(effectiveLimit)
                    .map(StoredResult::toRef)
                    .toList();
        } catch (IOException e) {
            logger.warn("Failed to list context results: {}", e.getMessage());
            return List.of();
        }
    }

    private Optional<StoredResult> readResult(Path path) {
        try {
            return Optional.of(objectMapper.readValue(path.toFile(), StoredResult.class));
        } catch (IOException e) {
            logger.warn("Failed to read context result {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.isBlank() || expected.equals(actual);
    }

    private String preview(String content) {
        if (content == null || content.length() <= previewChars) {
            return content;
        }
        return content.substring(0, previewChars);
    }

    private String safeSegment(String value) {
        return value == null || value.isBlank()
                ? "unknown"
                : value.replaceAll("[:/\\\\*?\"<>|]", "_");
    }
}
