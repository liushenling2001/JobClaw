package io.jobclaw.agent.catalog;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class FileAgentCatalogStore implements AgentCatalogStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path agentsDir;

    public FileAgentCatalogStore(String dirPath) {
        this.agentsDir = Paths.get(dirPath);
        try {
            Files.createDirectories(agentsDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize agent directory: " + agentsDir, e);
        }
    }

    @Override
    public AgentCatalogEntry save(AgentCatalogEntry entry) {
        try {
            Files.createDirectories(agentsDir);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(resolveFile(entry.code()).toFile(), entry);
            return entry;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save agent entry: " + entry.code(), e);
        }
    }

    @Override
    public Optional<AgentCatalogEntry> findById(String agentId) {
        return listAgents().stream()
                .filter(entry -> entry.agentId().equals(agentId))
                .findFirst();
    }

    @Override
    public Optional<AgentCatalogEntry> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        Path file = resolveFile(code);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(file.toFile(), AgentCatalogEntry.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load agent entry: " + code, e);
        }
    }

    @Override
    public Optional<AgentCatalogEntry> findByAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return Optional.empty();
        }
        return listAgents().stream()
                .filter(entry -> entry.aliases() != null && entry.aliases().stream().anyMatch(existing -> existing.equalsIgnoreCase(alias)))
                .findFirst();
    }

    @Override
    public List<AgentCatalogEntry> listAgents() {
        if (!Files.exists(agentsDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(agentsDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::readEntry)
                    .sorted(Comparator.comparing(AgentCatalogEntry::updatedAt).reversed())
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list agents from " + agentsDir, e);
        }
    }

    @Override
    public boolean delete(String agentId) {
        Optional<AgentCatalogEntry> entry = findById(agentId);
        if (entry.isEmpty()) {
            return false;
        }
        try {
            return Files.deleteIfExists(resolveFile(entry.get().code()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete agent entry: " + entry.get().code(), e);
        }
    }

    private AgentCatalogEntry readEntry(Path path) {
        try {
            return MAPPER.readValue(path.toFile(), AgentCatalogEntry.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read agent entry from " + path, e);
        }
    }

    private Path resolveFile(String code) {
        return agentsDir.resolve(code + ".json");
    }
}
