package io.jobclaw.agent.manifest;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.tools.ManifestTool;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks manifest state touched by tools in the current run.
 *
 * This is only a prompt aid. It does not create manifests, choose items,
 * block tools, or decide task completion.
 */
@Component
public class ActiveManifestRegistry {

    private static final int MAX_MANIFESTS_PER_RUN = 3;

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
                nullSafe(record.artifactPath()),
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
            if (!state.artifactPath().isBlank()) {
                sb.append("  artifactPath: ").append(state.artifactPath()).append("\n");
            }
            sb.append("  total: ").append(state.total()).append("\n");
            sb.append("  pending: ").append(state.pending()).append("\n");
            sb.append("  running: ").append(state.running()).append("\n");
            sb.append("  done: ").append(state.done()).append("\n");
            sb.append("  failed: ").append(state.failed()).append("\n");
            sb.append("  updatedAt: ").append(state.updatedAt()).append("\n");
            sb.append("  next: continue with this manifestId; use manifest(status, includeItems='pending', limit='1') if you need the next item.\n");
        }
        sb.append("Rules:\n");
        sb.append("- Do not call manifest.create again for the same current task just because earlier tool output left the prompt window.\n");
        sb.append("- If real current-task items are missing, use manifest.add_items with only those missing ids.\n");
        sb.append("- This frame is limited to manifests touched in the current run.\n");
        return sb.toString();
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

    private static String key(String sessionKey, String runId) {
        return sessionKey + "::" + runId;
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Counts(int total, int pending, int running, int done, int failed) {
    }

    public record ActiveManifestState(
            String sessionKey,
            String runId,
            String manifestId,
            String taskKey,
            String artifactPath,
            int total,
            int pending,
            int running,
            int done,
            int failed,
            Instant updatedAt
    ) {
    }
}
