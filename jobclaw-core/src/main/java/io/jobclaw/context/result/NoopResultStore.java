package io.jobclaw.context.result;

import java.util.List;
import java.util.Optional;

public class NoopResultStore implements ResultStore {
    @Override
    public ContextRef save(String sessionKey, String runId, String sourceType, String sourceName, String content) {
        throw new UnsupportedOperationException("ResultStore is not configured");
    }

    @Override
    public Optional<StoredResult> find(String refId) {
        return Optional.empty();
    }

    @Override
    public List<ContextRef> list(String sessionKey, String runId, int limit) {
        return List.of();
    }
}
