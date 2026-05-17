package io.jobclaw.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.ExecutionEvent;
import io.jobclaw.agent.manifest.ActiveManifestRegistry;
import io.jobclaw.agent.skill.ActiveSkillRegistry;
import io.jobclaw.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit multi-item task ledger. It is never auto-created by the framework.
 */
@Component
public class ManifestTool {

    private static final int DEFAULT_ITEM_LIMIT = 10;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path rootDir;
    private final ActiveManifestRegistry activeManifestRegistry;
    private final ActiveSkillRegistry activeSkillRegistry;
    private final Object manifestLock = new Object();

    @Autowired
    public ManifestTool(Config config, ActiveManifestRegistry activeManifestRegistry, ActiveSkillRegistry activeSkillRegistry) {
        this(Paths.get(config.getWorkspacePath(), ".jobclaw", "manifests"), activeManifestRegistry, activeSkillRegistry);
    }

    ManifestTool(Path rootDir) {
        this(rootDir, new ActiveManifestRegistry(), new ActiveSkillRegistry());
    }

    ManifestTool(Path rootDir, ActiveManifestRegistry activeManifestRegistry) {
        this(rootDir, activeManifestRegistry, new ActiveSkillRegistry());
    }

    ManifestTool(Path rootDir, ActiveManifestRegistry activeManifestRegistry, ActiveSkillRegistry activeSkillRegistry) {
        this.rootDir = rootDir;
        this.activeManifestRegistry = activeManifestRegistry != null ? activeManifestRegistry : new ActiveManifestRegistry();
        this.activeSkillRegistry = activeSkillRegistry != null ? activeSkillRegistry : new ActiveSkillRegistry();
    }

    @Tool(name = "manifest", description = "Explicit multi-item task ledger. Use create only after you know the real item list. create is idempotent per session taskKey and merges missing items instead of replacing existing work. Items must be real task objects, not examples or placeholders. artifactPath is the intermediate aggregate path; finalArtifactPath is only the expected final output path. executionMode=managed means the framework may run the item loop for an active runner skill; it does not generate the final artifact by itself. Actions: create, status, start, done, fail, add_items, reset.")
    public String manifest(
            @ToolParam(description = "Action: create/status/start/done/fail/add_items/reset") String action,
            @ToolParam(description = "Manifest id returned by create; required except for create", required = false) String manifestId,
            @ToolParam(description = "Stable task key for idempotent create in this session. Reuse the same key for the same current batch task.", required = false) String taskKey,
            @ToolParam(description = "Items for create/add_items. Prefer a JSON array of real objects with stable id and title/path. Do not pass example names, placeholders, or fake ids.", required = false) String items,
            @ToolParam(description = "Optional JSON schema/columns description for this batch task", required = false) String schema,
            @ToolParam(description = "Item id for start/done/fail", required = false) String itemId,
            @ToolParam(description = "Intermediate aggregate artifact path for create, or item artifact path for done/fail.", required = false) String artifactPath,
            @ToolParam(description = "Context reference id for an item result/evidence", required = false) String resultRefId,
            @ToolParam(description = "Short note/result summary", required = false) String note,
            @ToolParam(description = "Failure message for fail", required = false) String error,
            @ToolParam(description = "Include item status in status response: pending/running/done/failed/all", required = false) String includeItems,
            @ToolParam(description = "Maximum items returned by status; default 10", required = false) String limit,
            @ToolParam(description = "Explicitly reset an existing manifest on create/reset. Defaults false.", required = false) String reset,
            @ToolParam(description = "Optional create mode. Use managed only with an active runner skill; direct manifests remain ledger-only.", required = false) String executionMode,
            @ToolParam(description = "Optional expected final artifact path. The active skill finalize step is responsible for creating it.", required = false) String finalArtifactPath,
            @ToolParam(description = "Optional expected final artifact type, for example xlsx/pdf/docx/csv/json/report.", required = false) String finalArtifactType
    ) {
        String normalizedAction = action == null || action.isBlank()
                ? "status"
                : action.trim().toLowerCase(Locale.ROOT);
        synchronized (manifestLock) {
            try {
                return switch (normalizedAction) {
                    case "create" -> create(taskKey, items, schema, artifactPath, parseBoolean(reset), executionMode,
                            finalArtifactPath, finalArtifactType);
                    case "status" -> status(required(manifestId, "manifestId"), includeItems,
                            parseInt(limit, DEFAULT_ITEM_LIMIT), executionMode, finalArtifactPath, finalArtifactType);
                    case "start" -> updateStatus(required(manifestId, "manifestId"), required(itemId, "itemId"),
                            "running", artifactPath, resultRefId, note, null);
                    case "done" -> updateStatus(required(manifestId, "manifestId"), required(itemId, "itemId"),
                            "done", artifactPath, resultRefId, note, null);
                    case "fail", "failed" -> updateStatus(required(manifestId, "manifestId"), required(itemId, "itemId"),
                            "failed", artifactPath, resultRefId, note, error);
                    case "add_items" -> addItems(required(manifestId, "manifestId"), items);
                    case "reset" -> reset(required(manifestId, "manifestId"), parseBoolean(reset));
                    default -> "Error: unsupported manifest action: " + action;
                };
            } catch (IllegalArgumentException e) {
                return "Error: " + e.getMessage();
            } catch (Exception e) {
                return "Error: manifest operation failed: " + e.getMessage();
            }
        }
    }

    public String manifest(String action,
                           String manifestId,
                           String taskKey,
                           String items,
                           String schema,
                           String itemId,
                           String artifactPath,
                           String resultRefId,
                           String note,
                           String error,
                           String includeItems,
                           String limit,
                           String reset) {
        return manifest(action, manifestId, taskKey, items, schema, itemId, artifactPath, resultRefId,
                note, error, includeItems, limit, reset, null, null, null);
    }

    public String manifest(String action,
                           String manifestId,
                           String taskKey,
                           String items,
                           String schema,
                           String itemId,
                           String artifactPath,
                           String resultRefId,
                           String note,
                           String error,
                           String includeItems,
                           String limit,
                           String reset,
                           String executionMode) {
        return manifest(action, manifestId, taskKey, items, schema, itemId, artifactPath, resultRefId,
                note, error, includeItems, limit, reset, executionMode, null, null);
    }

    public String manifest(String action,
                           String manifestId,
                           String taskKey,
                           String items,
                           String schema,
                           String itemId,
                           String artifactPath,
                           String resultRefId,
                           String note,
                           String error,
                           String includeItems,
                           String limit,
                           String reset,
                           String executionMode,
                           String finalArtifactPath,
                           String finalArtifactType,
                           String ignoredManagedParallelism) {
        return manifest(action, manifestId, taskKey, items, schema, itemId, artifactPath, resultRefId,
                note, error, includeItems, limit, reset, executionMode, finalArtifactPath, finalArtifactType);
    }

    private String create(String taskKey, String itemsValue, String schema, String artifactPath, boolean reset,
                          String executionMode, String finalArtifactPath, String finalArtifactType) throws IOException {
        List<ManifestItem> parsedItems = parseItems(itemsValue);
        if (parsedItems.isEmpty()) {
            return "Error: items are required for manifest.create";
        }
        String contractError = validateManagedRunnerContract(schema, artifactPath, executionMode);
        if (!contractError.isBlank()) {
            return "Error: managed manifest contract is incomplete for the active runner skill. "
                    + contractError
                    + " Fix the manifest.create arguments only; do not process items until create succeeds.";
        }

        String sessionKey = currentSessionKey();
        String normalizedTaskKey = normalizeTaskKey(taskKey);
        String fingerprint = fingerprint(normalizedTaskKey, parsedItems, schema);

        Optional<ManifestRecord> existing = hasExplicitTaskKey(taskKey)
                ? findByTaskKey(sessionKey, normalizedTaskKey)
                : findByFingerprint(sessionKey, fingerprint);
        if (existing.isPresent() && !reset) {
            ManifestRecord record = existing.get();
            AddItemsResult added = addParsedItems(record, parsedItems);
            if (artifactPath != null && !artifactPath.isBlank() && nullSafe(record.artifactPath()).isBlank()) {
                record.artifactPath = artifactPath.trim();
            }
            String normalizedExecutionMode = normalizeExecutionMode(executionMode);
            if (!normalizedExecutionMode.isBlank() && nullSafe(record.executionMode()).isBlank()) {
                record.executionMode = normalizedExecutionMode;
            }
            applyFinalArtifactFields(record, finalArtifactPath, finalArtifactType);
            record.updatedAt = Instant.now();
            save(record);
            activeManifestRegistry.update(record);
            publishManifestEvent(record, "create_existing", null, "Manifest already exists");
            return "Manifest already exists.\n\n" + formatSummary(record)
                    + "\n\ncreate is idempotent for the same taskKey in this session. Continue using this manifestId. "
                    + "Items added: " + added.added() + ", duplicates skipped: " + added.duplicates() + ". "
                    + "Do not call manifest.create again for the same task. "
                    + "If the current task state shows missing eligible items, use add_items with only those missing items. "
                    + "Use reset=true only when the user asks to rebuild the ledger."
                    + "\n\n" + nextActionGuidance(record);
        }

        ManifestRecord record = existing.orElseGet(() -> new ManifestRecord(
                "mf-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                sessionKey,
                AgentExecutionContext.getCurrentRunId(),
                normalizedTaskKey,
                fingerprint,
                schema,
                nullSafe(artifactPath),
                normalizeExecutionMode(executionMode),
                nullSafe(finalArtifactPath),
                normalizeArtifactType(finalArtifactType),
                new LinkedHashMap<>(),
                Instant.now(),
                Instant.now()
        ));

        if (reset) {
            record.items().clear();
            record.artifactPath = nullSafe(artifactPath);
            record.schema = schema;
            record.executionMode = normalizeExecutionMode(executionMode);
            record.finalArtifactPath = nullSafe(finalArtifactPath);
            record.finalArtifactType = normalizeArtifactType(finalArtifactType);
            record.updatedAt = Instant.now();
        }
        AddItemsResult added = addParsedItems(record, parsedItems);
        save(record);
        activeManifestRegistry.update(record);
        publishManifestEvent(record, "create", null, "Manifest ready");
        return "Manifest ready.\n\n" + formatSummary(record)
                + "\n\nItems added: " + added.added() + ", duplicates skipped: " + added.duplicates()
                + "\n\n" + nextActionGuidance(record);
    }

    private String addItems(String manifestId, String itemsValue) throws IOException {
        ManifestRecord record = load(manifestId);
        List<ManifestItem> parsedItems = parseItems(itemsValue);
        if (parsedItems.isEmpty()) {
            return "Error: items are required for manifest.add_items";
        }
        AddItemsResult added = addParsedItems(record, parsedItems);
        save(record);
        activeManifestRegistry.update(record);
        publishManifestEvent(record, "add_items", null, "Manifest items updated");
        return "Manifest items updated.\n\n" + formatSummary(record)
                + "\n\nItems added: " + added.added() + ", duplicates skipped: " + added.duplicates()
                + "\n\n" + nextActionGuidance(record);
    }

    private String status(String manifestId, String includeItems, int limit, String executionMode,
                          String finalArtifactPath, String finalArtifactType) throws IOException {
        ManifestRecord record = load(manifestId);
        boolean changed = false;
        String normalizedExecutionMode = normalizeExecutionMode(executionMode);
        if (!normalizedExecutionMode.isBlank() && nullSafe(record.executionMode()).isBlank()) {
            record.executionMode = normalizedExecutionMode;
            changed = true;
        }
        if (applyFinalArtifactFields(record, finalArtifactPath, finalArtifactType)) {
            changed = true;
        }
        if (changed) {
            record.updatedAt = Instant.now();
            save(record);
        }
        activeManifestRegistry.update(record);
        publishManifestEvent(record, "status", null, "Manifest status checked");
        StringBuilder sb = new StringBuilder(formatSummary(record));
        List<ManifestItem> items = selectItems(record, includeItems, Math.max(1, limit));
        if (!items.isEmpty()) {
            sb.append("\n\nItems:\n");
            for (ManifestItem item : items) {
                sb.append("- ").append(item.id())
                        .append(" | ").append(item.status())
                        .append(" | ").append(nullSafe(item.title()));
                if (!nullSafe(item.artifactPath()).isBlank()) {
                    sb.append(" | artifact=").append(item.artifactPath());
                }
                if (!nullSafe(item.resultRefId()).isBlank()) {
                    sb.append(" | refId=").append(item.resultRefId());
                }
                if (!nullSafe(item.error()).isBlank()) {
                    sb.append(" | error=").append(item.error());
                }
                sb.append("\n");
            }
        }
        sb.append("\n").append(nextActionGuidance(record));
        return sb.toString();
    }

    private String updateStatus(String manifestId,
                                String itemId,
                                String status,
                                String artifactPath,
                                String resultRefId,
                                String note,
                                String error) throws IOException {
        ManifestRecord record = load(manifestId);
        ManifestItem item = record.items().get(itemId);
        if (item == null) {
            return "Error: manifest item not found: " + itemId;
        }
        ManifestItem updated = new ManifestItem(
                item.id(),
                item.title(),
                status,
                coalesce(artifactPath, item.artifactPath()),
                coalesce(resultRefId, item.resultRefId()),
                coalesce(note, item.note()),
                coalesce(error, item.error()),
                item.createdAt(),
                Instant.now()
        );
        record.items().put(itemId, updated);
        if (artifactPath != null && !artifactPath.isBlank() && nullSafe(record.artifactPath()).isBlank()) {
            record.artifactPath = artifactPath.trim();
        }
        record.updatedAt = Instant.now();
        save(record);
        activeManifestRegistry.update(record);
        publishManifestEvent(record, status, itemId, "Manifest item " + itemId + " -> " + status);
        return "Manifest item updated.\n\n" + formatSummary(record)
                + "\n\nItem: " + itemId + " -> " + status
                + "\n\n" + nextActionGuidance(record);
    }

    private String reset(String manifestId, boolean confirmed) throws IOException {
        if (!confirmed) {
            return "Error: reset requires reset=true";
        }
        ManifestRecord record = load(manifestId);
        for (ManifestItem item : new ArrayList<>(record.items().values())) {
            record.items().put(item.id(), new ManifestItem(
                    item.id(), item.title(), "pending", item.artifactPath(), item.resultRefId(),
                    null, null, item.createdAt(), Instant.now()
            ));
        }
        record.updatedAt = Instant.now();
        save(record);
        activeManifestRegistry.update(record);
        publishManifestEvent(record, "reset", null, "Manifest reset");
        return "Manifest reset.\n\n" + formatSummary(record)
                + "\n\n" + nextActionGuidance(record);
    }

    private String nextActionGuidance(ManifestRecord record) {
        Counts counts = counts(record);
        StringBuilder sb = new StringBuilder("Next action guidance:\n");
        sb.append("- Continue using manifestId `").append(record.manifestId()).append("`; do not call manifest.create again for this same task.\n");
        sb.append("- If the current task state shows missing eligible items, call manifest.add_items with only those missing item ids; do not rebuild the ledger.\n");
        if (counts.running() > 0) {
            sb.append("- There are running item(s). Finish each running item with manifest.done or manifest.fail before starting unrelated work.\n");
        } else if (counts.pending() > 0) {
            sb.append("- There are pending item(s). Use manifest.status(includeItems='pending', limit='1' or '5') to get the next item(s), then process them and mark done/fail.\n");
        } else {
            sb.append("- No pending items remain. Follow the active skill or task instructions for any required post-manifest step.\n");
        }
        if (!nullSafe(record.artifactPath()).isBlank()) {
            sb.append("- Current artifactPath: ").append(record.artifactPath()).append("\n");
        }
        if (!nullSafe(record.finalArtifactPath()).isBlank()) {
            sb.append("- Required final artifact: ").append(record.finalArtifactPath());
            if (!nullSafe(record.finalArtifactType()).isBlank()) {
                sb.append(" (type=").append(record.finalArtifactType()).append(")");
            }
            sb.append(finalArtifactReady(record) ? " [ready]\n" : " [not ready]\n");
        }
        if ("managed".equalsIgnoreCase(nullSafe(record.executionMode()))) {
            sb.append("- executionMode=managed: the framework will keep returning to this manifest until running/pending items are closed");
            if (!nullSafe(record.finalArtifactPath()).isBlank()) {
                sb.append(" and the declared final artifact is ready");
            }
            sb.append(".\n");
        } else {
            sb.append("- Manifest is a ledger only; it does not execute items by itself.");
        }
        return sb.toString();
    }

    private String validateManagedRunnerContract(String schema, String artifactPath, String executionMode) {
        if (!"managed".equalsIgnoreCase(normalizeExecutionMode(executionMode))) {
            return "";
        }
        String sessionKey = currentSessionKey();
        String runId = AgentExecutionContext.getCurrentRunId();
        if (sessionKey.isBlank() || runId == null || runId.isBlank()) {
            return "";
        }
        if (!activeSkillRegistry.hasManagedRunnerRuntime(sessionKey, runId)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (schema == null || schema.isBlank()) {
            sb.append("schema is required; ");
        }
        if (artifactPath == null || artifactPath.isBlank()) {
            sb.append("artifactPath is required for intermediate results; ");
        }
        String skillContractError = activeSkillRegistry.managedRunnerContractError(sessionKey, runId);
        if (!skillContractError.isBlank()) {
            sb.append(skillContractError);
        }
        return sb.toString().trim();
    }

    private void publishManifestEvent(ManifestRecord record, String action, String itemId, String content) {
        Counts counts = counts(record);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "manifest");
        metadata.put("action", nullSafe(action));
        metadata.put("manifestId", record.manifestId());
        metadata.put("taskKey", nullSafe(record.taskKey()));
        metadata.put("executionMode", nullSafe(record.executionMode()));
        metadata.put("artifactPath", nullSafe(record.artifactPath()));
        metadata.put("finalArtifactPath", nullSafe(record.finalArtifactPath()));
        metadata.put("finalArtifactType", nullSafe(record.finalArtifactType()));
        metadata.put("finalArtifactReady", finalArtifactReady(record));
        metadata.put("total", counts.total());
        metadata.put("pending", counts.pending());
        metadata.put("running", counts.running());
        metadata.put("done", counts.done());
        metadata.put("failed", counts.failed());
        if (itemId != null && !itemId.isBlank()) {
            metadata.put("itemId", itemId);
            ManifestItem item = record.items().get(itemId);
            if (item != null) {
                metadata.put("itemStatus", nullSafe(item.status()));
                metadata.put("itemArtifactPath", nullSafe(item.artifactPath()));
                metadata.put("itemResultRefId", nullSafe(item.resultRefId()));
                metadata.put("itemError", nullSafe(item.error()));
            }
        }
        AgentExecutionContext.publishEvent(new ExecutionEvent(
                record.sessionKey(),
                ExecutionEvent.EventType.CUSTOM,
                content,
                metadata
        ));
    }

    private AddItemsResult addParsedItems(ManifestRecord record, List<ManifestItem> parsedItems) {
        int added = 0;
        int duplicates = 0;
        for (ManifestItem item : parsedItems) {
            if (record.items().containsKey(item.id())) {
                duplicates++;
                continue;
            }
            record.items().put(item.id(), item);
            added++;
        }
        record.updatedAt = Instant.now();
        return new AddItemsResult(added, duplicates);
    }

    private List<ManifestItem> parseItems(String value) throws IOException {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("[")) {
            List<Object> rawItems = readItemsArray(trimmed);
            List<ManifestItem> items = new ArrayList<>();
            int index = 1;
            for (Object rawItem : rawItems) {
                ManifestItem item = toManifestItem(rawItem, index);
                if (item != null) {
                    items.add(item);
                    index++;
                }
            }
            return items;
        }

        List<ManifestItem> items = new ArrayList<>();
        int index = 1;
        for (String line : trimmed.split("\\R|[,，]")) {
            String title = line.trim();
            if (title.isBlank()) {
                continue;
            }
            items.add(newItem(defaultItemId(index), title));
            index++;
        }
        return items;
    }

    private List<Object> readItemsArray(String value) throws IOException {
        IOException firstError = null;
        for (String candidate : itemArrayCandidates(value)) {
            try {
                return OBJECT_MAPPER.readValue(candidate, new TypeReference<>() {});
            } catch (IOException e) {
                if (firstError == null) {
                    firstError = e;
                }
            }
        }
        throw firstError != null ? firstError : new IOException("items is not a JSON array");
    }

    private List<String> itemArrayCandidates(String value) {
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, value);
        String stripped = stripJsonFence(value);
        addCandidate(candidates, stripped);
        addCandidate(candidates, repairObjectArrayCommas(stripped));
        String unquoted = unquoteJsonString(stripped);
        addCandidate(candidates, unquoted);
        addCandidate(candidates, repairObjectArrayCommas(unquoted));
        return candidates;
    }

    private void addCandidate(List<String> candidates, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (!trimmed.isBlank() && !candidates.contains(trimmed)) {
            candidates.add(trimmed);
        }
    }

    private String stripJsonFence(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        return trimmed.replaceFirst("(?s)^```[A-Za-z0-9_-]*\\s*", "")
                .replaceFirst("(?s)\\s*```\\s*$", "")
                .trim();
    }

    private String repairObjectArrayCommas(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim()
                .replaceAll("}\\s*\\{", "},{")
                .replaceAll("]\\s*\\[", "],[");
    }

    private String unquoteJsonString(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        String trimmed = value.trim();
        if (!((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            return trimmed;
        }
        String inner = trimmed.substring(1, trimmed.length() - 1);
        return inner.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .trim();
    }

    @SuppressWarnings("unchecked")
    private ManifestItem toManifestItem(Object rawItem, int index) {
        if (rawItem == null) {
            return null;
        }
        if (rawItem instanceof String text) {
            return newItem(defaultItemId(index), text);
        }
        if (rawItem instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            String id = firstNonBlank(map, "id", "itemId", "key");
            String title = firstNonBlank(map, "title", "name", "path", "file");
            if (title == null || title.isBlank()) {
                title = id;
            }
            if (id == null || id.isBlank()) {
                id = defaultItemId(index);
            }
            return newItem(id, title);
        }
        return newItem(defaultItemId(index), rawItem.toString());
    }

    private ManifestItem newItem(String id, String title) {
        Instant now = Instant.now();
        return new ManifestItem(id.trim(), nullSafe(title).trim(), "pending", null, null, null, null, now, now);
    }

    private List<ManifestItem> selectItems(ManifestRecord record, String status, int limit) {
        String normalized = status == null || status.isBlank() ? "" : status.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return List.of();
        }
        return record.items().values().stream()
                .filter(item -> "all".equals(normalized) || normalized.equals(item.status()))
                .sorted(Comparator.comparing(ManifestItem::id))
                .limit(limit)
                .toList();
    }

    private Optional<ManifestRecord> findByFingerprint(String sessionKey, String fingerprint) throws IOException {
        Path dir = sessionDir(sessionKey);
        if (!Files.exists(dir)) {
            return Optional.empty();
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> {
                        try {
                            return OBJECT_MAPPER.readValue(path.toFile(), ManifestRecord.class);
                        } catch (IOException ignored) {
                            return null;
                        }
                    })
                    .filter(record -> record != null && fingerprint.equals(record.fingerprint()))
                    .findFirst();
        }
    }

    private Optional<ManifestRecord> findByTaskKey(String sessionKey, String taskKey) throws IOException {
        Path dir = sessionDir(sessionKey);
        if (!Files.exists(dir)) {
            return Optional.empty();
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> {
                        try {
                            return OBJECT_MAPPER.readValue(path.toFile(), ManifestRecord.class);
                        } catch (IOException ignored) {
                            return null;
                        }
                    })
                    .filter(record -> record != null && taskKey.equals(record.taskKey()))
                    .max(Comparator.comparing(ManifestRecord::updatedAt));
        }
    }

    private ManifestRecord load(String manifestId) throws IOException {
        Path path = manifestPath(currentSessionKey(), manifestId);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("manifest not found: " + manifestId);
        }
        return OBJECT_MAPPER.readValue(path.toFile(), ManifestRecord.class);
    }

    private void save(ManifestRecord record) throws IOException {
        Path path = manifestPath(record.sessionKey(), record.manifestId());
        Files.createDirectories(path.getParent());
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), record);
    }

    private Path manifestPath(String sessionKey, String manifestId) {
        return sessionDir(sessionKey).resolve(manifestId + ".json");
    }

    private Path sessionDir(String sessionKey) {
        return rootDir.resolve(safePathSegment(sessionKey));
    }

    private String currentSessionKey() {
        String sessionKey = AgentExecutionContext.getCurrentSessionKey();
        return sessionKey == null || sessionKey.isBlank() ? "no-session" : sessionKey;
    }

    private String formatSummary(ManifestRecord record) {
        Counts counts = counts(record);
        return "Manifest: " + record.manifestId() + "\n"
                + "taskKey: " + nullSafe(record.taskKey()) + "\n"
                + "executionMode: " + nullSafe(record.executionMode()) + "\n"
                + "total: " + counts.total() + "\n"
                + "pending: " + counts.pending() + "\n"
                + "running: " + counts.running() + "\n"
                + "done: " + counts.done() + "\n"
                + "failed: " + counts.failed() + "\n"
                + "artifactPath: " + nullSafe(record.artifactPath()) + "\n"
                + "finalArtifactPath: " + nullSafe(record.finalArtifactPath()) + "\n"
                + "finalArtifactType: " + nullSafe(record.finalArtifactType()) + "\n"
                + "finalArtifactReady: " + finalArtifactReady(record) + "\n"
                + "updatedAt: " + record.updatedAt();
    }

    private Counts counts(ManifestRecord record) {
        int pending = 0;
        int running = 0;
        int done = 0;
        int failed = 0;
        for (ManifestItem item : record.items().values()) {
            switch (nullSafe(item.status())) {
                case "running" -> running++;
                case "done" -> done++;
                case "failed" -> failed++;
                default -> pending++;
            }
        }
        return new Counts(record.items().size(), pending, running, done, failed);
    }

    private String fingerprint(String taskKey, List<ManifestItem> items, String schema) {
        StringBuilder sb = new StringBuilder(nullSafe(taskKey)).append('\n').append(nullSafe(schema)).append('\n');
        items.stream()
                .map(item -> item.id() + "=" + nullSafe(item.title()))
                .sorted()
                .forEach(line -> sb.append(line).append('\n'));
        return sha256(sb.toString());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String normalizeTaskKey(String taskKey) {
        if (taskKey != null && !taskKey.isBlank()) {
            return taskKey.trim();
        }
        return "batch-task";
    }

    private String normalizeExecutionMode(String executionMode) {
        if (executionMode == null || executionMode.isBlank()) {
            return "";
        }
        String normalized = executionMode.trim().toLowerCase(Locale.ROOT);
        return "managed".equals(normalized) ? "managed" : "";
    }

    private String normalizeArtifactType(String finalArtifactType) {
        if (finalArtifactType == null || finalArtifactType.isBlank()) {
            return "";
        }
        return finalArtifactType.trim().toLowerCase(Locale.ROOT);
    }

    private boolean applyFinalArtifactFields(ManifestRecord record, String finalArtifactPath, String finalArtifactType) {
        if (record == null) {
            return false;
        }
        boolean changed = false;
        if (finalArtifactPath != null && !finalArtifactPath.isBlank()
                && nullSafe(record.finalArtifactPath()).isBlank()) {
            record.finalArtifactPath = finalArtifactPath.trim();
            changed = true;
        }
        String normalizedType = normalizeArtifactType(finalArtifactType);
        if (!normalizedType.isBlank() && nullSafe(record.finalArtifactType()).isBlank()) {
            record.finalArtifactType = normalizedType;
            changed = true;
        }
        return changed;
    }

    private boolean finalArtifactReady(ManifestRecord record) {
        String path = nullSafe(record.finalArtifactPath()).trim();
        if (path.isBlank()) {
            return false;
        }
        try {
            Path artifact = Path.of(path);
            return Files.isRegularFile(artifact) && Files.size(artifact) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasExplicitTaskKey(String taskKey) {
        return taskKey != null && !taskKey.isBlank();
    }

    private String defaultItemId(int index) {
        return "item-" + String.format("%03d", index);
    }

    private String firstNonBlank(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString().trim();
            }
        }
        return null;
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private String safePathSegment(String value) {
        return nullSafe(value).replaceAll("[:/\\\\*?\"<>|]", "_");
    }

    private int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean parseBoolean(String value) {
        return value != null && ("true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim()));
    }

    private String coalesce(String next, String previous) {
        return next == null || next.isBlank() ? previous : next.trim();
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }

    private record AddItemsResult(int added, int duplicates) {
    }

    private record Counts(int total, int pending, int running, int done, int failed) {
    }

    public static class ManifestRecord {
        private String manifestId;
        private String sessionKey;
        private String runId;
        private String taskKey;
        private String fingerprint;
        private String schema;
        private String artifactPath;
        private String executionMode;
        private String finalArtifactPath;
        private String finalArtifactType;
        private LinkedHashMap<String, ManifestItem> items = new LinkedHashMap<>();
        private Instant createdAt;
        private Instant updatedAt;

        public ManifestRecord() {
        }

        public ManifestRecord(String manifestId,
                              String sessionKey,
                              String runId,
                              String taskKey,
                              String fingerprint,
                              String schema,
                              String artifactPath,
                              String executionMode,
                              String finalArtifactPath,
                              String finalArtifactType,
                              LinkedHashMap<String, ManifestItem> items,
                              Instant createdAt,
                              Instant updatedAt) {
            this.manifestId = manifestId;
            this.sessionKey = sessionKey;
            this.runId = runId;
            this.taskKey = taskKey;
            this.fingerprint = fingerprint;
            this.schema = schema;
            this.artifactPath = artifactPath;
            this.executionMode = executionMode;
            this.finalArtifactPath = finalArtifactPath;
            this.finalArtifactType = finalArtifactType;
            this.items = items != null ? items : new LinkedHashMap<>();
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public String manifestId() {
            return manifestId;
        }

        public String sessionKey() {
            return sessionKey;
        }

        public String runId() {
            return runId;
        }

        public String taskKey() {
            return taskKey;
        }

        public String fingerprint() {
            return fingerprint;
        }

        public String schema() {
            return schema;
        }

        public String artifactPath() {
            return artifactPath;
        }

        public String executionMode() {
            return executionMode;
        }

        public String finalArtifactPath() {
            return finalArtifactPath;
        }

        public String finalArtifactType() {
            return finalArtifactType;
        }

        public LinkedHashMap<String, ManifestItem> items() {
            if (items == null) {
                items = new LinkedHashMap<>();
            }
            return items;
        }

        public Instant createdAt() {
            return createdAt;
        }

        public Instant updatedAt() {
            return updatedAt;
        }

        public String getManifestId() {
            return manifestId;
        }

        public void setManifestId(String manifestId) {
            this.manifestId = manifestId;
        }

        public String getSessionKey() {
            return sessionKey;
        }

        public void setSessionKey(String sessionKey) {
            this.sessionKey = sessionKey;
        }

        public String getRunId() {
            return runId;
        }

        public void setRunId(String runId) {
            this.runId = runId;
        }

        public String getTaskKey() {
            return taskKey;
        }

        public void setTaskKey(String taskKey) {
            this.taskKey = taskKey;
        }

        public String getFingerprint() {
            return fingerprint;
        }

        public void setFingerprint(String fingerprint) {
            this.fingerprint = fingerprint;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public String getArtifactPath() {
            return artifactPath;
        }

        public void setArtifactPath(String artifactPath) {
            this.artifactPath = artifactPath;
        }

        public String getExecutionMode() {
            return executionMode;
        }

        public void setExecutionMode(String executionMode) {
            this.executionMode = executionMode;
        }

        public String getFinalArtifactPath() {
            return finalArtifactPath;
        }

        public void setFinalArtifactPath(String finalArtifactPath) {
            this.finalArtifactPath = finalArtifactPath;
        }

        public String getFinalArtifactType() {
            return finalArtifactType;
        }

        public void setFinalArtifactType(String finalArtifactType) {
            this.finalArtifactType = finalArtifactType;
        }

        public LinkedHashMap<String, ManifestItem> getItems() {
            return items();
        }

        public void setItems(LinkedHashMap<String, ManifestItem> items) {
            this.items = items != null ? items : new LinkedHashMap<>();
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }
    }

    public record ManifestItem(
            String id,
            String title,
            String status,
            String artifactPath,
            String resultRefId,
            String note,
            String error,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
