package io.jobclaw.agent.completion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jobclaw.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CompletionRegistry {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private static final int DEFAULT_MAX_ATTEMPTS = 2;
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile(
            "[A-Za-z]:\\\\[^\\r\\n<>|?*\"]+"
    );

    private final Path manifestRootDir;
    private final ConcurrentHashMap<String, CompletionContract> contracts = new ConcurrentHashMap<>();

    @Autowired
    public CompletionRegistry(Config config) {
        this(Paths.get(config.getWorkspacePath(), ".jobclaw", "manifests"));
    }

    CompletionRegistry(Path manifestRootDir) {
        this.manifestRootDir = manifestRootDir;
    }

    public CompletionContract register(String sessionKey,
                                       String runId,
                                       String checksJson,
                                       String onFail,
                                       int maxAttempts) throws IOException {
        List<CompletionCheck> checks = parseChecks(checksJson);
        if (checks.isEmpty()) {
            throw new IllegalArgumentException("checks are required");
        }
        int effectiveMaxAttempts = maxAttempts > 0 ? Math.min(maxAttempts, 5) : DEFAULT_MAX_ATTEMPTS;
        Instant now = Instant.now();
        CompletionContract contract = new CompletionContract(
                sessionKey,
                runId,
                checks,
                onFail,
                effectiveMaxAttempts,
                0,
                now,
                now
        );
        contracts.put(key(sessionKey, runId), contract);
        return contract;
    }

    public CompletionGateResult evaluateForFinal(String sessionKey, String runId) {
        return evaluateForFinal(sessionKey, runId, "");
    }

    public CompletionGateResult evaluateForFinal(String sessionKey, String runId, String finalResponse) {
        CompletionContract contract = contracts.get(key(sessionKey, runId));
        if (contract == null) {
            return CompletionGateResult.unregistered();
        }
        List<String> failures = evaluateFailures(sessionKey, contract, finalResponse);
        if (failures.isEmpty()) {
            clear(sessionKey, runId);
            return new CompletionGateResult(true, true, false,
                    contract.failedAttempts(), contract.maxAttempts(), List.of(), contract.onFail());
        }
        int nextAttempts = contract.failedAttempts() + 1;
        CompletionContract updated = contract.withFailedAttempts(nextAttempts);
        contracts.put(key(sessionKey, runId), updated);
        return new CompletionGateResult(
                true,
                false,
                nextAttempts < updated.maxAttempts(),
                nextAttempts,
                updated.maxAttempts(),
                failures,
                updated.onFail()
        );
    }

    public CompletionGateResult status(String sessionKey, String runId) {
        CompletionContract contract = contracts.get(key(sessionKey, runId));
        if (contract == null) {
            return CompletionGateResult.unregistered();
        }
        List<String> failures = evaluateFailures(sessionKey, contract, "");
        return new CompletionGateResult(
                true,
                failures.isEmpty(),
                contract.failedAttempts() < contract.maxAttempts(),
                contract.failedAttempts(),
                contract.maxAttempts(),
                failures,
                contract.onFail()
        );
    }

    public boolean hasContract(String sessionKey, String runId) {
        return contracts.containsKey(key(sessionKey, runId));
    }

    public void clear(String sessionKey, String runId) {
        contracts.remove(key(sessionKey, runId));
    }

    private List<CompletionCheck> parseChecks(String checksJson) throws IOException {
        if (checksJson == null || checksJson.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rawChecks = OBJECT_MAPPER.readValue(checksJson, new TypeReference<>() {});
        List<CompletionCheck> checks = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> raw : rawChecks) {
            String type = stringValue(raw.get("type"));
            if (type.isBlank()) {
                throw new IllegalArgumentException("check " + index + " missing type");
            }
            checks.add(new CompletionCheck(
                    firstNonBlank(stringValue(raw.get("id")), type + "-" + index),
                    type,
                    stringValue(raw.get("path")),
                    stringValue(raw.get("manifestId")),
                    stringValue(raw.get("artifactType")),
                    stringValue(raw.get("outputDir"))
            ));
            index++;
        }
        return checks;
    }

    private List<String> evaluateFailures(String sessionKey, CompletionContract contract, String finalResponse) {
        List<String> failures = new ArrayList<>();
        for (CompletionCheck check : contract.checks()) {
            String failure = evaluateCheck(sessionKey, check, finalResponse);
            if (failure != null && !failure.isBlank()) {
                failures.add(failure);
            }
        }
        return failures;
    }

    private String evaluateCheck(String sessionKey, CompletionCheck check, String finalResponse) {
        String type = check.type() == null ? "" : check.type().trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "file_exists" -> checkFileExists(check);
            case "file_non_empty" -> checkFileNonEmpty(check);
            case "directory_exists" -> checkDirectoryExists(check);
            case "directory_non_empty" -> checkDirectoryNonEmpty(check);
            case "manifest_done" -> checkManifestDone(sessionKey, check);
            case "artifact_expected" -> checkArtifactExpected(check, finalResponse);
            default -> check.id() + ": unsupported check type: " + check.type();
        };
    }

    private String checkArtifactExpected(CompletionCheck check, String finalResponse) {
        String artifactType = firstNonBlank(check.artifactType(), "unspecified");
        String outputDir = firstNonBlank(check.outputDir(), "unspecified");
        List<Path> candidates = extractArtifactPaths(finalResponse, artifactType);
        if (candidates.isEmpty()) {
            return check.id() + ": expected artifact path was not found in the final response: type=" + artifactType
                    + ", outputDir=" + outputDir
                    + ". Before final response, state the full generated artifact path if it already exists; "
                    + "if it does not exist, continue the task and create it.";
        }
        if (candidates.size() > 1) {
            return check.id() + ": multiple candidate artifact paths were found in the final response for type="
                    + artifactType + ": " + candidates
                    + ". Before final response, state exactly one final artifact path.";
        }
        Path path = candidates.get(0);
        if ("directory".equalsIgnoreCase(artifactType)) {
            if (!Files.isDirectory(path)) {
                return check.id() + ": directory artifact missing: " + path
                        + ". Continue the task and create the directory artifact, then final answer with its full path.";
            }
            return null;
        }
        if (!Files.exists(path)) {
            return check.id() + ": artifact file missing: " + path
                    + ". Continue the task and create the artifact, then final answer with its full path.";
        }
        try {
            if (Files.size(path) <= 0) {
                return check.id() + ": artifact file is empty: " + path
                        + ". Continue the task and create a non-empty artifact, then final answer with its full path.";
            }
        } catch (IOException e) {
            return check.id() + ": cannot read artifact file size: " + e.getMessage();
        }
        return null;
    }

    private List<Path> extractArtifactPaths(String text, String artifactType) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalizedType = firstNonBlank(artifactType, "").toLowerCase(Locale.ROOT);
        List<String> extensions = knownExtensionsForArtifactType(normalizedType);
        Matcher matcher = WINDOWS_ABSOLUTE_PATH.matcher(text);
        List<Path> paths = new ArrayList<>();
        while (matcher.find()) {
            String rawPath = trimPathCandidate(matcher.group());
            if (rawPath.isBlank()) {
                continue;
            }
            if (!extensions.isEmpty()) {
                rawPath = trimToKnownExtension(rawPath, extensions);
                if (rawPath.isBlank()) {
                    continue;
                }
            }
            paths.add(Paths.get(rawPath));
        }
        return paths.stream()
                .distinct()
                .sorted(Comparator.comparing(Path::toString))
                .toList();
    }

    private List<String> knownExtensionsForArtifactType(String artifactType) {
        return switch (artifactType) {
            case "xlsx" -> List.of(".xlsx");
            case "xls" -> List.of(".xls");
            case "excel", "spreadsheet", "workbook" -> List.of(".xlsx", ".xls");
            case "jsonl" -> List.of(".jsonl");
            case "json" -> List.of(".json");
            case "pdf" -> List.of(".pdf");
            case "doc" -> List.of(".doc");
            case "docx", "word" -> List.of(".docx");
            case "csv" -> List.of(".csv");
            case "md", "markdown" -> List.of(".md");
            case "html" -> List.of(".html", ".htm");
            case "pptx", "slides", "presentation" -> List.of(".pptx");
            case "zip", "archive" -> List.of(".zip");
            case "txt", "text" -> List.of(".txt");
            default -> List.of();
        };
    }

    private String trimPathCandidate(String value) {
        String result = value == null ? "" : value.trim();
        while (!result.isEmpty() && ".,;，。；、)）]】'\"`".indexOf(result.charAt(result.length() - 1)) >= 0) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    private String trimToKnownExtension(String value, List<String> extensions) {
        String lower = value.toLowerCase(Locale.ROOT);
        int end = -1;
        for (String extension : extensions) {
            int index = lower.indexOf(extension);
            if (index >= 0) {
                int candidateEnd = index + extension.length();
                if (end < 0 || candidateEnd < end) {
                    end = candidateEnd;
                }
            }
        }
        return end < 0 ? "" : trimPathCandidate(value.substring(0, end));
    }

    private String checkFileExists(CompletionCheck check) {
        if (check.path() == null || check.path().isBlank()) {
            return check.id() + ": path is required";
        }
        Path path = Paths.get(check.path());
        if (!Files.exists(path)) {
            return check.id() + ": file missing: " + check.path();
        }
        return null;
    }

    private String checkFileNonEmpty(CompletionCheck check) {
        String existsFailure = checkFileExists(check);
        if (existsFailure != null) {
            return existsFailure;
        }
        try {
            if (Files.size(Paths.get(check.path())) <= 0) {
                return check.id() + ": file is empty: " + check.path();
            }
            return null;
        } catch (IOException e) {
            return check.id() + ": cannot read file size: " + e.getMessage();
        }
    }

    private String checkDirectoryExists(CompletionCheck check) {
        if (check.path() == null || check.path().isBlank()) {
            return check.id() + ": path is required";
        }
        Path path = Paths.get(check.path());
        if (!Files.isDirectory(path)) {
            return check.id() + ": directory missing: " + check.path();
        }
        return null;
    }

    private String checkDirectoryNonEmpty(CompletionCheck check) {
        String existsFailure = checkDirectoryExists(check);
        if (existsFailure != null) {
            return existsFailure;
        }
        try (var stream = Files.list(Paths.get(check.path()))) {
            if (stream.findAny().isEmpty()) {
                return check.id() + ": directory is empty: " + check.path();
            }
            return null;
        } catch (IOException e) {
            return check.id() + ": cannot read directory: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String checkManifestDone(String sessionKey, CompletionCheck check) {
        if (check.manifestId() == null || check.manifestId().isBlank()) {
            return check.id() + ": manifestId is required";
        }
        Path path = manifestRootDir.resolve(safePathSegment(sessionKey)).resolve(check.manifestId() + ".json");
        if (!Files.exists(path)) {
            return check.id() + ": manifest missing: " + check.manifestId();
        }
        try {
            Map<String, Object> record = OBJECT_MAPPER.readValue(path.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
            Object itemsObj = record.get("items");
            if (!(itemsObj instanceof Map<?, ?> items)) {
                return check.id() + ": manifest has no items";
            }
            int total = items.size();
            int pending = 0;
            int running = 0;
            int done = 0;
            int failed = 0;
            for (Object value : items.values()) {
                if (!(value instanceof Map<?, ?> item)) {
                    pending++;
                    continue;
                }
                String status = stringValue(((Map<String, Object>) item).get("status"));
                switch (status) {
                    case "running" -> running++;
                    case "done" -> done++;
                    case "failed" -> failed++;
                    default -> pending++;
                }
            }
            if (pending == 0 && running == 0 && done + failed == total) {
                return null;
            }
            return check.id() + ": manifest incomplete: total=" + total
                    + ", pending=" + pending
                    + ", running=" + running
                    + ", done=" + done
                    + ", failed=" + failed;
        } catch (Exception e) {
            return check.id() + ": cannot read manifest: " + e.getMessage();
        }
    }

    private String key(String sessionKey, String runId) {
        return firstNonBlank(sessionKey, "no-session") + "::" + firstNonBlank(runId, "no-run");
    }

    private String safePathSegment(String value) {
        return firstNonBlank(value, "no-session").replaceAll("[:/\\\\*?\"<>|]", "_");
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first.trim() : fallback;
    }
}
