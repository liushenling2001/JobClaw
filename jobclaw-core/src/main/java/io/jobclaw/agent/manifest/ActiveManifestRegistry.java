package io.jobclaw.agent.manifest;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.tools.ManifestTool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks manifest state touched by tools in the current run.
 *
 * This is only a prompt aid. It does not create manifests, block tools,
 * or decide task completion. Managed manifests use the recorded state to
 * keep the model focused on one ledger item at a time.
 */
@Component
public class ActiveManifestRegistry {

    private static final int MAX_MANIFESTS_PER_RUN = 3;
    private static final int MAX_SCHEDULER_ITEMS = 8;

    private final Map<String, LinkedHashMap<String, ActiveManifestState>> states = new ConcurrentHashMap<>();

    public void update(ManifestTool.ManifestRecord record) {
        if (record == null || isBlank(record.manifestId())) {
            return;
        }
        AgentExecutionContext.ExecutionScope scope = AgentExecutionContext.getCurrentScope();
        String sessionKey = scope != null ? scope.sessionKey() : record.sessionKey();
        String runId = scope != null ? scope.runId() : record.runId();
        if (isBlank(sessionKey) || isBlank(runId)) {
            return;
        }

        Counts counts = count(record);
        ActiveManifestState state = new ActiveManifestState(
                sessionKey,
                runId,
                record.manifestId(),
                nullSafe(record.taskKey()),
                nullSafe(record.schema()),
                nullSafe(record.executionMode()),
                nullSafe(record.artifactPath()),
                nullSafe(record.finalArtifactPath()),
                nullSafe(record.finalArtifactType()),
                finalArtifactReady(record.finalArtifactPath()),
                snapshot(firstItem(record, "running")),
                snapshot(firstItem(record, "pending")),
                snapshots(firstItems(record, "pending", MAX_SCHEDULER_ITEMS)),
                counts.total(),
                counts.pending(),
                counts.running(),
                counts.done(),
                counts.failed(),
                Instant.now()
        );

        states.compute(key(sessionKey, runId), (ignored, previous) -> {
            LinkedHashMap<String, ActiveManifestState> next = previous != null ? previous : new LinkedHashMap<>();
            next.put(record.manifestId(), state);
            while (next.size() > MAX_MANIFESTS_PER_RUN) {
                String oldest = next.entrySet().stream()
                        .min(Comparator.comparing(entry -> entry.getValue().updatedAt()))
                        .map(Map.Entry::getKey)
                        .orElse(null);
                if (oldest == null) {
                    break;
                }
                next.remove(oldest);
            }
            return next;
        });
    }

    public String formatForPrompt(String sessionKey, String runId) {
        LinkedHashMap<String, ActiveManifestState> runStates = states.get(key(sessionKey, runId));
        if (runStates == null || runStates.isEmpty()) {
            return "";
        }

        List<ActiveManifestState> ordered = runStates.values().stream()
                .sorted(Comparator.comparing(ActiveManifestState::updatedAt).reversed())
                .limit(MAX_MANIFESTS_PER_RUN)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("[[JOBCLAW_CURRENT_RUN_MANIFESTS]]\n");
        sb.append("Current run manifest state from manifest tool results. This is state only; it does not execute items or replace the skill.\n");
        for (ActiveManifestState state : ordered) {
            sb.append("- manifestId: ").append(state.manifestId()).append("\n");
            if (!state.taskKey().isBlank()) {
                sb.append("  taskKey: ").append(state.taskKey()).append("\n");
            }
            if (!state.executionMode().isBlank()) {
                sb.append("  executionMode: ").append(state.executionMode()).append("\n");
            }
            if (!state.schema().isBlank()) {
                sb.append("  schema: ").append(state.schema()).append("\n");
            }
            if (!state.artifactPath().isBlank()) {
                sb.append("  artifactPath: ").append(state.artifactPath()).append("\n");
            }
            if (!state.finalArtifactPath().isBlank()) {
                sb.append("  finalArtifactPath: ").append(state.finalArtifactPath()).append("\n");
                if (!state.finalArtifactType().isBlank()) {
                    sb.append("  finalArtifactType: ").append(state.finalArtifactType()).append("\n");
                }
                sb.append("  finalArtifactReady: ").append(finalArtifactReady(state)).append("\n");
            }
            sb.append("  total: ").append(state.total()).append("\n");
            sb.append("  pending: ").append(state.pending()).append("\n");
            sb.append("  running: ").append(state.running()).append("\n");
            sb.append("  done: ").append(state.done()).append("\n");
            sb.append("  failed: ").append(state.failed()).append("\n");
            if (state.runningItem() != null) {
                sb.append("  currentRunningItem: ").append(formatItem(state.runningItem())).append("\n");
            } else if (state.nextPendingItem() != null) {
                sb.append("  nextPendingItem: ").append(formatItem(state.nextPendingItem())).append("\n");
            }
            sb.append("  updatedAt: ").append(state.updatedAt()).append("\n");
            sb.append("  next: continue this manifest; for managed mode, work only on the single running item, or the single next pending item.\n");
        }
        sb.append("Rules:\n");
        sb.append("- Do not call manifest.create again for the same current task just because earlier tool output left the prompt window.\n");
        sb.append("- If real current-task items are missing, use manifest.add_items with only those missing ids.\n");
        sb.append("- This frame is limited to manifests touched in the current run.\n");
        return sb.toString();
    }

    public Optional<ActiveManifestState> findManagedBlockingState(String sessionKey, String runId) {
        LinkedHashMap<String, ActiveManifestState> runStates = states.get(key(sessionKey, runId));
        if (runStates == null || runStates.isEmpty()) {
            return Optional.empty();
        }
        return runStates.values().stream()
                .filter(state -> "managed".equalsIgnoreCase(state.executionMode()))
                .filter(state -> state.running() > 0
                        || state.pending() > 0
                        || (!state.finalArtifactPath().isBlank() && !finalArtifactReady(state)))
                .sorted(Comparator.comparing(ActiveManifestState::updatedAt).reversed())
                .findFirst();
    }

    public Optional<ActiveManifestState> findManagedHandoffState(String sessionKey, String runId) {
        LinkedHashMap<String, ActiveManifestState> runStates = states.get(key(sessionKey, runId));
        if (runStates == null || runStates.isEmpty()) {
            return Optional.empty();
        }
        return runStates.values().stream()
                .filter(state -> "managed".equalsIgnoreCase(state.executionMode()))
                .filter(state -> state.running() == 0 && state.pending() == 0)
                .filter(state -> state.finalArtifactPath().isBlank() || finalArtifactReady(state))
                .sorted(Comparator.comparing(ActiveManifestState::updatedAt).reversed())
                .findFirst();
    }

    public Optional<ActiveManifestState> findManagedClosedState(String sessionKey, String runId) {
        LinkedHashMap<String, ActiveManifestState> runStates = states.get(key(sessionKey, runId));
        if (runStates == null || runStates.isEmpty()) {
            return Optional.empty();
        }
        return runStates.values().stream()
                .filter(state -> "managed".equalsIgnoreCase(state.executionMode()))
                .filter(state -> state.running() == 0 && state.pending() == 0)
                .sorted(Comparator.comparing(ActiveManifestState::updatedAt).reversed())
                .findFirst();
    }

    public void clear(String sessionKey, String runId) {
        if (isBlank(sessionKey) || isBlank(runId)) {
            return;
        }
        states.remove(key(sessionKey, runId));
    }

    private Counts count(ManifestTool.ManifestRecord record) {
        int pending = 0;
        int running = 0;
        int done = 0;
        int failed = 0;
        for (ManifestTool.ManifestItem item : record.items().values()) {
            switch (nullSafe(item.status())) {
                case "running" -> running++;
                case "done" -> done++;
                case "failed" -> failed++;
                default -> pending++;
            }
        }
        return new Counts(record.items().size(), pending, running, done, failed);
    }

    private ManifestTool.ManifestItem firstItem(ManifestTool.ManifestRecord record, String status) {
        return record.items().values().stream()
                .filter(item -> status.equalsIgnoreCase(nullSafe(item.status())))
                .sorted(Comparator.comparing(ManifestTool.ManifestItem::id))
                .findFirst()
                .orElse(null);
    }

    private List<ManifestTool.ManifestItem> firstItems(ManifestTool.ManifestRecord record, String status, int limit) {
        return record.items().values().stream()
                .filter(item -> status.equalsIgnoreCase(nullSafe(item.status())))
                .sorted(Comparator.comparing(ManifestTool.ManifestItem::id))
                .limit(Math.max(1, limit))
                .toList();
    }

    private ActiveManifestItem snapshot(ManifestTool.ManifestItem item) {
        if (item == null) {
            return null;
        }
        return new ActiveManifestItem(
                nullSafe(item.id()),
                nullSafe(item.title()),
                nullSafe(item.status()),
                nullSafe(item.artifactPath()),
                nullSafe(item.resultRefId()),
                nullSafe(item.note()),
                nullSafe(item.error())
        );
    }

    private List<ActiveManifestItem> snapshots(List<ManifestTool.ManifestItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .map(this::snapshot)
                .filter(item -> item != null)
                .toList();
    }

    private String formatItem(ActiveManifestItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append(item.id()).append(" | ").append(item.status()).append(" | ").append(item.title());
        if (!item.artifactPath().isBlank()) {
            sb.append(" | artifact=").append(item.artifactPath());
        }
        if (!item.resultRefId().isBlank()) {
            sb.append(" | refId=").append(item.resultRefId());
        }
        if (!item.error().isBlank()) {
            sb.append(" | error=").append(item.error());
        }
        return sb.toString();
    }

    private static String key(String sessionKey, String runId) {
        return sessionKey + "::" + runId;
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean finalArtifactReady(String finalArtifactPath) {
        if (isBlank(finalArtifactPath)) {
            return false;
        }
        try {
            Path path = Path.of(finalArtifactPath.trim());
            return Files.isRegularFile(path) && Files.size(path) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean finalArtifactReady(ActiveManifestState state) {
        if (state == null || isBlank(state.finalArtifactPath())) {
            return false;
        }
        if (state.finalArtifactReady()) {
            return true;
        }
        return finalArtifactReady(state.finalArtifactPath());
    }

    private record Counts(int total, int pending, int running, int done, int failed) {
    }

    public record ActiveManifestState(
            String sessionKey,
            String runId,
            String manifestId,
            String taskKey,
            String schema,
            String executionMode,
            String artifactPath,
            String finalArtifactPath,
            String finalArtifactType,
            boolean finalArtifactReady,
            ActiveManifestItem runningItem,
            ActiveManifestItem nextPendingItem,
            List<ActiveManifestItem> pendingQueue,
            int total,
            int pending,
            int running,
            int done,
            int failed,
            Instant updatedAt
    ) {
        public ActiveManifestState withNextPendingItem(ActiveManifestItem item) {
            return new ActiveManifestState(
                    sessionKey,
                    runId,
                    manifestId,
                    taskKey,
                    schema,
                    executionMode,
                    artifactPath,
                    finalArtifactPath,
                    finalArtifactType,
                    finalArtifactReady,
                    runningItem,
                    item,
                    pendingQueue,
                    total,
                    pending,
                    running,
                    done,
                    failed,
                    updatedAt
            );
        }

        public List<ActiveManifestItem> pendingQueue() {
            return pendingQueue != null ? pendingQueue : List.of();
        }
    }

    public record ActiveManifestItem(
            String id,
            String title,
            String status,
            String artifactPath,
            String resultRefId,
            String note,
            String error
    ) {
    }
}
