package io.jobclaw.agent.learning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FileLearningCandidateStore implements LearningCandidateStore {

    private final Path file;
    private final ObjectMapper objectMapper;

    public FileLearningCandidateStore(String directory) {
        this.file = Path.of(directory).resolve("candidates.json");
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public synchronized List<LearningCandidate> list() {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(file);
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(json, new TypeReference<List<LearningCandidate>>() {});
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    @Override
    public synchronized void saveAll(List<LearningCandidate> candidates) {
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(file.toFile(), candidates != null ? candidates : List.of());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save learning candidates: " + e.getMessage(), e);
        }
    }
}
