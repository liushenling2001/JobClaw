package io.jobclaw.summary.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jobclaw.conversation.StoredMessage;
import io.jobclaw.summary.ChunkSummary;
import io.jobclaw.summary.MemoryFact;
import io.jobclaw.summary.SessionSummaryRecord;
import io.jobclaw.summary.SummaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileSummaryService implements SummaryService {

    private static final Logger logger = LoggerFactory.getLogger(FileSummaryService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final Pattern WINDOWS_PATH = Pattern.compile("[A-Za-z]:\\\\[^\\s\"']+");
    private static final Pattern UNIX_PATH = Pattern.compile("(?:\\./|\\.\\./|/)[^\\s\"']+");
    private static final Pattern RELATIVE_FILE = Pattern.compile("\\b(?:[\\w.-]+/)+[\\w.-]+\\.[A-Za-z0-9]{1,10}\\b");

    private final Path rootDir;

    public FileSummaryService(String conversationStorePath) {
        this.rootDir = conversationStorePath == null || conversationStorePath.isBlank()
                ? Paths.get(System.getProperty("user.home"), ".jobclaw", "workspace", "sessions", "conversation")
                : Paths.get(conversationStorePath);
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            logger.warn("Failed to initialize summary store directory: {}", e.getMessage());
        }
    }

    @Override
    public void saveChunkSummary(ChunkSummary chunkSummary) {
        if (chunkSummary == null || chunkSummary.sessionId() == null || chunkSummary.sessionId().isBlank()) {
            return;
        }
        writeNdjson(sessionDir(chunkSummary.sessionId()).resolve("chunk-summaries.ndjson"), chunkSummary);
    }

    @Override
    public void saveSessionSummary(SessionSummaryRecord sessionSummary) {
        if (sessionSummary == null || sessionSummary.sessionId() == null || sessionSummary.sessionId().isBlank()) {
            return;
        }
        try {
            Path sessionDir = sessionDir(sessionSummary.sessionId());
            Files.createDirectories(sessionDir);
            Files.writeString(
                    sessionDir.resolve("session-summary.json"),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(sessionSummary),
                    StandardCharsets.UTF_8
            );
        } catch (Exception e) {
            logger.warn("Failed to persist session summary for {}: {}", sessionSummary.sessionId(), e.getMessage());
        }
    }

    @Override
    public void replaceMemoryFacts(String sessionId, List<MemoryFact> facts) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            Path sessionDir = sessionDir(sessionId);
            Files.createDirectories(sessionDir);
            Path factFile = sessionDir.resolve("memory-facts.ndjson");
            List<String> lines = new ArrayList<>();
            if (facts != null) {
                for (MemoryFact fact : facts) {
                    lines.add(MAPPER.writeValueAsString(fact));
                }
            }
            Files.write(factFile, lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warn("Failed to persist memory facts for {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void summarizePendingChunks(String sessionId) {
        // Summary generation is orchestrated by SessionSummarizer in the current migration phase.
    }

    @Override
    public Optional<ChunkSummary> getChunkSummary(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            return Optional.empty();
        }
        return listAllChunkSummaries().stream()
                .filter(summary -> chunkId.equals(summary.chunkId()))
                .findFirst();
    }

    @Override
    public List<ChunkSummary> listChunkSummaries(String sessionId) {
        return readNdjson(sessionDir(sessionId).resolve("chunk-summaries.ndjson"), ChunkSummary.class);
    }

    @Override
    public Optional<SessionSummaryRecord> getSessionSummary(String sessionId) {
        Path summaryFile = sessionDir(sessionId).resolve("session-summary.json");
        if (!Files.exists(summaryFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(Files.readString(summaryFile), SessionSummaryRecord.class));
        } catch (Exception e) {
            logger.warn("Failed to read session summary for {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<MemoryFact> extractFacts(String sessionId, List<StoredMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        Map<String, MemoryFact> facts = new LinkedHashMap<>();
        for (StoredMessage message : messages) {
            if (message == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            extractFileFacts(sessionId, message, facts);
            extractConstraintFacts(sessionId, message, facts);
        }
        return new ArrayList<>(facts.values());
    }

    @Override
    public List<MemoryFact> listMemoryFacts(String sessionId) {
        return readNdjson(sessionDir(sessionId).resolve("memory-facts.ndjson"), MemoryFact.class);
    }

    private void extractFileFacts(String sessionId, StoredMessage message, Map<String, MemoryFact> facts) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        collectMatches(WINDOWS_PATH, message.content(), paths);
        collectMatches(UNIX_PATH, message.content(), paths);
        collectMatches(RELATIVE_FILE, message.content(), paths);

        for (String path : paths) {
            String normalized = path.trim();
            String factId = "file:" + normalized.toLowerCase();
            facts.putIfAbsent(factId, new MemoryFact(
                    factId,
                    sessionId,
                    "session",
                    "important_file",
                    "conversation",
                    "mentions",
                    normalized,
                    Map.of(
                            "messageId", message.messageId(),
                            "sequence", message.sequence(),
                            "role", message.role()
                    ),
                    0.6,
                    true,
                    Instant.now(),
                    Instant.now()
            ));
        }
    }

    private void extractConstraintFacts(String sessionId, StoredMessage message, Map<String, MemoryFact> facts) {
        String content = message.content().trim();
        String lower = content.toLowerCase();
        if (!(lower.contains("must") || lower.contains("only") || lower.contains("should not")
                || content.contains("不要") || content.contains("必须") || content.contains("只能"))) {
            return;
        }

        String objectText = content.length() > 240 ? content.substring(0, 240) : content;
        String factId = "constraint:" + Integer.toHexString(objectText.toLowerCase().hashCode());
        facts.putIfAbsent(factId, new MemoryFact(
                factId,
                sessionId,
                "session",
                "constraint",
                "user",
                "requires",
                objectText,
                Map.of(
                        "messageId", message.messageId(),
                        "sequence", message.sequence(),
                        "role", message.role()
                ),
                0.7,
                true,
                Instant.now(),
                Instant.now()
        ));
    }

    private void collectMatches(Pattern pattern, String input, LinkedHashSet<String> target) {
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) {
            target.add(matcher.group());
        }
    }

    private <T> List<T> readNdjson(Path file, Class<T> type) {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<T> items = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                items.add(MAPPER.readValue(line, type));
            }
        } catch (Exception e) {
            logger.warn("Failed to read {}: {}", file, e.getMessage());
        }
        return items;
    }

    private List<ChunkSummary> listAllChunkSummaries() {
        if (!Files.exists(rootDir)) {
            return List.of();
        }
        List<ChunkSummary> summaries = new ArrayList<>();
        try (var stream = Files.list(rootDir)) {
            stream.filter(Files::isDirectory)
                    .forEach(sessionDir -> summaries.addAll(
                            readNdjson(sessionDir.resolve("chunk-summaries.ndjson"), ChunkSummary.class)
                    ));
        } catch (IOException e) {
            logger.warn("Failed to scan chunk summaries: {}", e.getMessage());
        }
        return summaries;
    }

    private void writeNdjson(Path file, Object item) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    MAPPER.writeValueAsString(item) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    Files.exists(file)
                            ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE
            );
        } catch (Exception e) {
            logger.warn("Failed to append {}: {}", file, e.getMessage());
        }
    }

    private Path sessionDir(String sessionId) {
        return rootDir.resolve(safeName(sessionId));
    }

    private String safeName(String value) {
        return value.replaceAll("[:/\\\\*?\"<>|]", "_");
    }
}
