package io.jobclaw.summary;

import io.jobclaw.conversation.StoredMessage;

import java.util.List;
import java.util.Optional;

public interface SummaryService {

    void saveChunkSummary(ChunkSummary chunkSummary);

    void saveSessionSummary(SessionSummaryRecord sessionSummary);

    void replaceMemoryFacts(String sessionId, List<MemoryFact> facts);

    void summarizePendingChunks(String sessionId);

    Optional<ChunkSummary> getChunkSummary(String chunkId);

    List<ChunkSummary> listChunkSummaries(String sessionId);

    Optional<SessionSummaryRecord> getSessionSummary(String sessionId);

    List<MemoryFact> extractFacts(String sessionId, List<StoredMessage> messages);

    List<MemoryFact> listMemoryFacts(String sessionId);
}
