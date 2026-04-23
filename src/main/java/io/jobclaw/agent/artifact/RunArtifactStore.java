package io.jobclaw.agent.artifact;

import java.util.List;

public interface RunArtifactStore {

    RunArtifact save(String runId, String stepId, String name, String content, String summary);

    List<RunArtifact> list(String runId);

    String buildIndex(String runId);
}
