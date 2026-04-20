package io.jobclaw.agent.checkpoint;

import java.util.Optional;

public interface TaskCheckpointStore {

    void save(TaskCheckpoint checkpoint);

    Optional<TaskCheckpoint> latest(String sessionId);
}
