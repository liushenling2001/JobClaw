package io.jobclaw.agent.checkpoint;

import io.jobclaw.agent.planning.TaskPlanningMode;

import java.time.Instant;
import java.util.List;

public record TaskCheckpoint(
        String sessionId,
        String runId,
        String taskInput,
        TaskPlanningMode planningMode,
        int pendingSubtasks,
        String lastStableSummary,
        String nextAction,
        List<SubtaskSnapshot> subtasks,
        Instant savedAt
) {
    public record SubtaskSnapshot(
            String id,
            String title,
            String status,
            String summary
    ) {
    }
}
