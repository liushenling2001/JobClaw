package io.jobclaw.retrieval;

import io.jobclaw.conversation.StoredMessage;
import io.jobclaw.summary.ChunkSummary;
import io.jobclaw.summary.MemoryFact;
import io.jobclaw.summary.SessionSummaryRecord;

import java.util.List;
import java.util.Optional;

public record RetrievalBundle(
        List<StoredMessage> historyMessages,
        List<ChunkSummary> chunkSummaries,
        List<MemoryFact> memoryFacts,
        Optional<SessionSummaryRecord> sessionSummary
) {
}
