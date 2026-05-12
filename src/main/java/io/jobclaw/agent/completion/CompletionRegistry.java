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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CompletionRegistry {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private static final int DEFAULT_MAX_ATTEMPTS = 2;

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
        CompletionContract contract = contracts.get(key(sessionKey, runId));
        if (contract == null) {
            return CompletionGateResult.unregistered();
        }
        List<String> failures = evaluateFailures(sessionKey, contract);
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
        List<String> failures = evaluateFailures(sessionKey, contract);
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
                    stringValue(raw.get("manifestId"))
            ));
            index++;
        }
        return checks;
    }

    private List<String> evaluateFailures(String sessionKey, CompletionContract contract) {
        List<String> failures = new ArrayList<>();
        for (CompletionCheck check : contract.checks()) {
            String failure = evaluateCheck(sessionKey, check);
            if (failure != null && !failure.isBlank()) {
                failures.add(failure);
            }
        }
        return failures;
    }

    private String evaluateCheck(String sessionKey, CompletionCheck check) {
        String type = check.type() == null ? "" : check.type().trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "file_exists" -> checkFileExists(check);
            case "file_non_empty" -> checkFileNonEmpty(check);
            case "manifest_done" -> checkManifestDone(sessionKey, check);
            default -> check.id() + ": unsupported check type: " + check.type();
        };
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
