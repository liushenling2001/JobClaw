package io.jobclaw.retrieval;

import io.jobclaw.conversation.StoredMessage;
import io.jobclaw.summary.ChunkSummary;
import io.jobclaw.summary.MemoryFact;
import io.jobclaw.summary.SessionSummaryRecord;

import java.util.List;
import java.util.Optional;

public interface RetrievalService {

    List<StoredMessage> searchHistory(SearchQuery query);

    List<ChunkSummary> searchSummaries(SearchQuery query);

    List<MemoryFact> searchMemory(SearchQuery query);

    Optional<SessionSummaryRecord> getSessionSummary(String sessionId);

    RetrievalBundle retrieveForContext(String sessionId, String userInput);
}
