package io.jobclaw.run;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jobclaw.agent.ExecutionEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class FileRunStore implements RunStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final Path root;

    public FileRunStore(Path stateRoot) {
        this.root = stateRoot.resolve(".jobclaw").resolve("runs");
    }

    @Override
    public synchronized void save(RunRecord record) throws IOException {
        Path runDir = runDir(record.getRunId());
        Files.createDirectories(runDir);
        Path target = runDir.resolve("run.json");
        Path temp = runDir.resolve("run.json.tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), record);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        appendIndex(record);
    }

    @Override
    public Optional<RunRecord> get(String runId) throws IOException {
        Path path = runDir(runId).resolve("run.json");
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(MAPPER.readValue(path.toFile(), RunRecord.class));
    }

    @Override
    public List<RunRecord> list(int limit) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<RunRecord> records = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path path : stream.filter(Files::isDirectory).toList()) {
                Path runFile = path.resolve("run.json");
                if (Files.isRegularFile(runFile)) {
                    records.add(MAPPER.readValue(runFile.toFile(), RunRecord.class));
                }
            }
        }
        records.sort(Comparator.comparing(RunRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        if (limit > 0 && records.size() > limit) {
            return records.subList(0, limit);
        }
        return records;
    }

    @Override
    public synchronized void appendEvent(String runId, ExecutionEvent event) throws IOException {
        Path runDir = runDir(runId);
        Files.createDirectories(runDir);
        Files.writeString(
                runDir.resolve("events.ndjson"),
                MAPPER.writeValueAsString(event.toSseData()) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    @Override
    public List<ExecutionEvent> readEvents(String runId, int limit) throws IOException {
        Path path = runDir(runId).resolve("events.ndjson");
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int from = limit > 0 ? Math.max(0, lines.size() - limit) : 0;
        List<ExecutionEvent> events = new ArrayList<>();
        for (String line : lines.subList(from, lines.size())) {
            if (line == null || line.isBlank()) {
                continue;
            }
            Map<String, Object> data = MAPPER.readValue(line, new TypeReference<>() {});
            ExecutionEvent event = ExecutionEvent.fromSseData(data);
            if (event != null) {
                events.add(event);
            }
        }
        return events;
    }

    @Override
    public synchronized void saveArtifacts(String runId, List<String> artifacts) throws IOException {
        Path runDir = runDir(runId);
        Files.createDirectories(runDir);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(runDir.resolve("artifacts.json").toFile(),
                artifacts != null ? artifacts : List.of());
    }

    @Override
    public List<String> readArtifacts(String runId) throws IOException {
        Path path = runDir(runId).resolve("artifacts.json");
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        return MAPPER.readValue(path.toFile(), new TypeReference<>() {});
    }

    private void appendIndex(RunRecord record) throws IOException {
        Files.createDirectories(root);
        Files.writeString(
                root.resolve("index.jsonl"),
                MAPPER.writeValueAsString(Map.of(
                        "runId", record.getRunId(),
                        "sessionKey", record.getSessionKey(),
                        "status", record.getStatus() != null ? record.getStatus().name() : "",
                        "updatedAt", record.getUpdatedAt() != null ? record.getUpdatedAt().toString() : ""
                )) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    private Path runDir(String runId) {
        String safe = runId == null || runId.isBlank()
                ? "unknown"
                : runId.replaceAll("[:/\\\\*?\"<>|]", "_");
        return root.resolve(safe);
    }
}
