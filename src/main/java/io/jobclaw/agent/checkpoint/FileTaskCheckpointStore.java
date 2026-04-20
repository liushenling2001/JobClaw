package io.jobclaw.agent.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class FileTaskCheckpointStore implements TaskCheckpointStore {

    private final Path root;
    private final ObjectMapper objectMapper;

    public FileTaskCheckpointStore(String root) {
        this.root = Path.of(root);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public void save(TaskCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.sessionId() == null || checkpoint.sessionId().isBlank()) {
            return;
        }
        try {
            Files.createDirectories(root);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(fileForSession(checkpoint.sessionId()).toFile(), checkpoint);
        } catch (IOException ignored) {
        }
    }

    @Override
    public Optional<TaskCheckpoint> latest(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Path file = fileForSession(sessionId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(file.toFile(), TaskCheckpoint.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Path fileForSession(String sessionId) {
        String safeName = sessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return root.resolve(safeName + ".json");
    }
}
