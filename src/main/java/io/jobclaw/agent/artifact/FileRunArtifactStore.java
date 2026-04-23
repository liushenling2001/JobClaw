package io.jobclaw.agent.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class FileRunArtifactStore implements RunArtifactStore {

    private final Path root;
    private final ObjectMapper objectMapper;

    public FileRunArtifactStore(String root) {
        this.root = Path.of(root);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public RunArtifact save(String runId, String stepId, String name, String content, String summary) {
        String safeRunId = safe(runId);
        String safeStepId = safe(stepId);
        String safeName = safe(name);
        String effectiveContent = content != null ? content : "";
        try {
            Path stepDir = root.resolve(safeRunId).resolve("steps");
            Files.createDirectories(stepDir);
            Path file = stepDir.resolve(safeStepId + "-" + safeName + ".md");
            Files.writeString(file, effectiveContent, StandardCharsets.UTF_8);
            RunArtifact artifact = new RunArtifact(
                    runId,
                    stepId,
                    name,
                    root.relativize(file).toString().replace('\\', '/'),
                    summary != null ? summary : "",
                    effectiveContent.length(),
                    Instant.now()
            );
            writeIndex(safeRunId, artifact);
            return artifact;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save run artifact: " + e.getMessage(), e);
        }
    }

    @Override
    public List<RunArtifact> list(String runId) {
        Path index = indexPath(safe(runId));
        if (!Files.exists(index)) {
            return List.of();
        }
        try {
            RunArtifact[] artifacts = objectMapper.readValue(index.toFile(), RunArtifact[].class);
            return artifacts != null ? List.of(artifacts) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    @Override
    public String buildIndex(String runId) {
        List<RunArtifact> artifacts = list(runId);
        if (artifacts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[Run Artifact Index]\n");
        for (RunArtifact artifact : artifacts) {
            sb.append("- ")
                    .append(artifact.stepId())
                    .append("/")
                    .append(artifact.name())
                    .append(": ")
                    .append(artifact.path())
                    .append(" (")
                    .append(artifact.contentLength())
                    .append(" chars)");
            if (artifact.summary() != null && !artifact.summary().isBlank()) {
                sb.append(" - ").append(artifact.summary());
            }
            sb.append("\n");
        }
        sb.append("Use these process artifacts instead of re-reading or replaying large prior outputs.");
        return sb.toString();
    }

    private synchronized void writeIndex(String safeRunId, RunArtifact artifact) throws IOException {
        List<RunArtifact> artifacts = new ArrayList<>(list(artifact.runId()));
        artifacts.add(artifact);
        artifacts.sort(Comparator.comparing(RunArtifact::createdAt));
        Path index = indexPath(safeRunId);
        Files.createDirectories(index.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(index.toFile(), artifacts);
    }

    private Path indexPath(String safeRunId) {
        return root.resolve(safeRunId).resolve("state").resolve("artifacts.json");
    }

    private String safe(String value) {
        String text = value == null || value.isBlank() ? "unknown" : value;
        return text.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
