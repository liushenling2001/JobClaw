package io.jobclaw.agent.turn;

import io.jobclaw.runtime.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class TurnExecutionLedger {

    private static final Set<String> NON_WORK_EVIDENCE_TOOLS = Set.of(
            "skills", "tool_search", "completion", "user_input"
    );

    private final String sessionId;
    private final String runId;
    private final Set<String> successfulEvidenceIds = ConcurrentHashMap.newKeySet();
    private final Set<String> usedToolNames = ConcurrentHashMap.newKeySet();
    private final AtomicInteger failedToolCalls = new AtomicInteger();
    private final StringBuilder lastAssistantSegment = new StringBuilder();
    private long assistantToolRevision;
    private boolean segmentTooLong;
    private final AtomicInteger successfulWorkCalls = new AtomicInteger();
    private final AtomicReference<Status> status = new AtomicReference<>(Status.RUNNING);

    public TurnExecutionLedger(String sessionId, String runId) {
        this.sessionId = sessionId;
        this.runId = runId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String runId() {
        return runId;
    }

    public Status status() {
        return status.get();
    }

    public void markStatus(Status nextStatus) {
        if (nextStatus != null) {
            status.set(nextStatus);
        }
    }

    public synchronized void beginModelAttempt() {
        lastAssistantSegment.setLength(0);
        assistantToolRevision = 0;
        segmentTooLong = false;
    }

    public synchronized void syncToolRevision(long revision) {
        if (assistantToolRevision != revision) {
            assistantToolRevision = revision;
            lastAssistantSegment.setLength(0);
            segmentTooLong = false;
        }
    }

    public synchronized void recordAssistantContent(String content, long revision) {
        syncToolRevision(revision);
        if (content == null || segmentTooLong) {
            return;
        }
        // This guard only inspects short promises, never retains a second full response.
        if (lastAssistantSegment.length() + content.length() > FinalizationNudgeGuard.MAX_CANDIDATE_CHARS) {
            lastAssistantSegment.setLength(0);
            segmentTooLong = true;
            return;
        }
        lastAssistantSegment.append(content);
    }

    public synchronized String lastAssistantSegment() {
        return lastAssistantSegment.toString();
    }

    public int successfulWorkCalls() {
        return successfulWorkCalls.get();
    }

    public void recordToolResult(String toolName, ToolExecutionResult result) {
        if (toolName == null || toolName.isBlank() || result == null) {
            return;
        }
        String normalizedName = toolName.trim().toLowerCase();
        usedToolNames.add(normalizedName);
        if (!result.success()) {
            failedToolCalls.incrementAndGet();
            return;
        }
        if (!NON_WORK_EVIDENCE_TOOLS.contains(normalizedName)) {
            successfulWorkCalls.incrementAndGet();
            String evidenceId = result.toolId() != null && !result.toolId().isBlank()
                    ? result.toolId()
                    : normalizedName + "-" + successfulEvidenceIds.size();
            successfulEvidenceIds.add(evidenceId);
        }
    }

    public boolean hasSuccessfulWorkEvidence() {
        return !successfulEvidenceIds.isEmpty();
    }

    public List<String> successfulEvidenceIds() {
        return new ArrayList<>(successfulEvidenceIds);
    }

    public Set<String> usedToolNames() {
        return Set.copyOf(usedToolNames);
    }

    public int failedToolCalls() {
        return failedToolCalls.get();
    }

    public enum Status {
        RUNNING,
        WAITING_USER,
        COMPLETED,
        BLOCKED,
        FAILED,
        CANCELLED
    }

}
