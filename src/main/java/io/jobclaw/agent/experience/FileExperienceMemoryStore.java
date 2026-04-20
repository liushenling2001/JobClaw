package io.jobclaw.agent.experience;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FileExperienceMemoryStore implements ExperienceMemoryStore {

    private final Path file;
    private final ObjectMapper objectMapper;

    public FileExperienceMemoryStore(String directory) {
        this.file = Path.of(directory).resolve("accepted-experience.json");
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public synchronized List<ExperienceMemory> list() {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(file);
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(json, new TypeReference<List<ExperienceMemory>>() {});
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    @Override
    public synchronized void saveAll(List<ExperienceMemory> memories) {
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(file.toFile(), memories != null ? memories : List.of());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save experience memory: " + e.getMessage(), e);
        }
    }
}
