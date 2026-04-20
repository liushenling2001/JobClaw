package io.jobclaw.agent.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FileWorkflowMemoryStore implements WorkflowMemoryStore {

    private final Path file;
    private final ObjectMapper objectMapper;

    public FileWorkflowMemoryStore(String directory) {
        this.file = Path.of(directory).resolve("recipes.json");
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public synchronized List<WorkflowRecipe> list() {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(file);
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(json, new TypeReference<List<WorkflowRecipe>>() {});
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    @Override
    public synchronized void saveAll(List<WorkflowRecipe> recipes) {
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(file.toFile(), recipes != null ? recipes : List.of());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save workflow recipes: " + e.getMessage(), e);
        }
    }
}
