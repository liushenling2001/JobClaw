package io.jobclaw.context.result;

import java.util.List;
import java.util.Optional;

public interface ResultStore {
    ContextRef save(String sessionKey, String runId, String sourceType, String sourceName, String content);

    Optional<StoredResult> find(String refId);

    List<ContextRef> list(String sessionKey, String runId, int limit);
}
