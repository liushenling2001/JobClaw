package io.jobclaw.run;

import io.jobclaw.agent.ExecutionEvent;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface RunStore {
    void save(RunRecord record) throws IOException;

    Optional<RunRecord> get(String runId) throws IOException;

    List<RunRecord> list(int limit) throws IOException;

    void appendEvent(String runId, ExecutionEvent event) throws IOException;

    List<ExecutionEvent> readEvents(String runId, int limit) throws IOException;

    void saveArtifacts(String runId, List<String> artifacts) throws IOException;

    List<String> readArtifacts(String runId) throws IOException;
}
