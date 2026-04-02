# JobClaw Backend Conversation Refactor

## Goal

This refactor is backend-only. The web UI and current API contracts should remain usable without user-visible workflow changes.

Primary goals:

1. Preserve raw conversation history with append-only storage.
2. Replace flattened history prompts with structured message-list context assembly.
3. Upgrade summarization into chunk summaries, session summaries, and extracted memory facts.
4. Introduce an internal search layer based on SQLite + FTS5.
5. Separate history retrieval, summary retrieval, and memory retrieval.

Non-goals for this phase:

1. No frontend redesign.
2. No mandatory new search UI.
3. No immediate vector database rollout.

## Compatibility Constraint

The migration must be user-invisible from the current web console perspective.

That means:

1. Existing chat APIs continue to work.
2. Existing session APIs continue to return compatible data.
3. Existing frontend pages do not need schema changes for the first migration slice.
4. New storage and retrieval layers are internal implementation details behind compatibility adapters.

## Current Problems

### Storage

- Session history is destructively truncated after summarization.
- Tool messages are not a reliable part of the stored conversation history.
- `Session.summary` is a single text field and cannot support layered retrieval.

### Context Assembly

- History is eventually flattened into a single prompt string.
- Role boundaries and tool-call structure are lost.
- Summary, memory, and recent history are not composed as independent layers.

### Retrieval

- Current retrieval is tightly coupled to `MemoryStore`.
- There is no clear distinction between raw history lookup and long-term memory lookup.
- No proper text index exists for raw session history.

### Architecture

- `AgentLoop` is responsible for too many concerns.
- `ContextBuilder` depends directly on the legacy session model.
- Storage, summary, retrieval, and prompt assembly are not isolated behind interfaces.

## Target Backend Architecture

```text
Web/API
  -> AgentOrchestrator
    -> AgentLoop
      -> ContextAssembler
        -> ConversationStore
        -> SummaryService
        -> RetrievalService
      -> LLM Provider / Tool execution

Background workers
  -> ChunkSummaryWorker
  -> SessionSummaryWorker
  -> MemoryFactWorker
  -> SearchIndexer
```

## Core Rule

The source of truth becomes append-only raw conversation records, not the legacy in-memory session summary model.

Derived layers:

1. `messages`
2. `message_chunks`
3. `chunk_summaries`
4. `session_summaries`
5. `memory_facts`
6. FTS indexes

Legacy `SessionManager` remains as a compatibility facade during migration.

## Data Model

## `sessions`

- `session_id TEXT PRIMARY KEY`
- `title TEXT`
- `created_at TEXT`
- `updated_at TEXT`
- `last_message_at TEXT`
- `status TEXT`
- `tags_json TEXT`

## `messages`

Append-only raw messages.

- `message_id TEXT PRIMARY KEY`
- `session_id TEXT NOT NULL`
- `seq INTEGER NOT NULL`
- `role TEXT NOT NULL`
- `content TEXT`
- `tool_name TEXT`
- `tool_call_id TEXT`
- `tool_args_json TEXT`
- `tool_result_json TEXT`
- `metadata_json TEXT`
- `created_at TEXT NOT NULL`

Constraints:

- unique `(session_id, seq)`

## `message_chunks`

- `chunk_id TEXT PRIMARY KEY`
- `session_id TEXT NOT NULL`
- `start_seq INTEGER NOT NULL`
- `end_seq INTEGER NOT NULL`
- `message_count INTEGER NOT NULL`
- `token_estimate INTEGER`
- `status TEXT`
- `created_at TEXT`
- `updated_at TEXT`

## `chunk_summaries`

- `chunk_id TEXT PRIMARY KEY`
- `session_id TEXT NOT NULL`
- `summary_text TEXT NOT NULL`
- `entities_json TEXT`
- `topics_json TEXT`
- `decisions_json TEXT`
- `open_questions_json TEXT`
- `version INTEGER NOT NULL`
- `created_at TEXT`

## `session_summaries`

- `session_id TEXT PRIMARY KEY`
- `summary_text TEXT NOT NULL`
- `active_goals_json TEXT`
- `constraints_json TEXT`
- `important_files_json TEXT`
- `source_chunk_end_seq INTEGER`
- `version INTEGER NOT NULL`
- `updated_at TEXT`

## `memory_facts`

- `fact_id TEXT PRIMARY KEY`
- `session_id TEXT`
- `scope TEXT`
- `fact_type TEXT`
- `subject TEXT`
- `predicate TEXT`
- `object_text TEXT`
- `evidence_json TEXT`
- `confidence REAL`
- `is_active INTEGER`
- `created_at TEXT`
- `updated_at TEXT`

## Internal Search Layer

SQLite FTS5 should be introduced first for backend retrieval only.

Suggested indexes:

```sql
CREATE VIRTUAL TABLE message_search USING fts5(
  message_id,
  session_id,
  role,
  content,
  tokenize = 'unicode61'
);

CREATE VIRTUAL TABLE chunk_summary_search USING fts5(
  chunk_id,
  session_id,
  summary_text,
  tokenize = 'unicode61'
);

CREATE VIRTUAL TABLE memory_fact_search USING fts5(
  fact_id,
  session_id,
  fact_text,
  tokenize = 'unicode61'
);
```

This layer is initially for context retrieval, not for new UI features.

## Retrieval Separation

### History Search

Purpose:

- retrieve raw message snippets
- support session/time/role filters
- return evidence windows for prompt assembly

### Summary Search

Purpose:

- retrieve chunk-level and session-level summaries
- support topic recall without loading full raw history

### Memory Search

Purpose:

- retrieve extracted constraints, preferences, and durable facts

These must not be merged into one store abstraction again.

## Context Assembly

Prompt assembly should move to message-list composition.

Suggested layers:

1. base system instructions
2. session summary
3. retrieved chunk summaries
4. retrieved memory facts
5. recent raw history
6. tool result messages
7. current user message

This allows backend improvements while preserving current API behavior.

## Required Backend Interfaces

### `ConversationStore`

Responsibilities:

- append raw messages
- read recent messages
- read sessions
- manage chunks

### `SummaryService`

Responsibilities:

- summarize chunks
- maintain session summaries
- extract durable facts

### `RetrievalService`

Responsibilities:

- search raw history
- search summaries
- search memory facts
- produce retrieval bundles for context assembly

### `ContextAssembler`

Responsibilities:

- build structured prompt messages
- enforce token budgets
- compose summary and retrieval layers

## Legacy Compatibility Strategy

The migration should keep `SessionManager` and current web endpoints alive while shifting the source of truth under the hood.

Suggested approach:

1. New writes go to append-only `ConversationStore`.
2. `SessionManager` becomes a projection/compatibility layer for old APIs.
3. Existing summary fields continue to be populated temporarily from `session_summaries`.
4. Existing session detail APIs can continue to return old shapes generated from the new store.

## Migration Stages

## Stage 1: Storage and Context Boundaries

Deliverables:

1. Introduce `ConversationStore`, `ContextAssembler`, `RetrievalService`, and `SummaryService`.
2. Add append-only storage for user, assistant, and tool messages.
3. Stop deleting raw history after summary generation.
4. Keep existing APIs compatible.

Success criteria:

1. New conversations retain raw history even after summarization.
2. Tool outputs are persisted.
3. Prompt construction no longer requires flattening full history into one user message.

## Stage 2: Internal Search

Deliverables:

1. Add SQLite + FTS5 indexing for raw messages.
2. Implement backend history retrieval for context assembly.
3. Keep search internal unless explicitly exposed later.

Success criteria:

1. Retrieval can fetch older relevant raw messages by text query.
2. `ContextAssembler` can mix recent history with retrieved history.

## Stage 3: Summary and Fact Layers

Deliverables:

1. Chunk generation
2. Chunk summaries
3. Session summary aggregation
4. Memory fact extraction

Success criteria:

1. Prompt assembly can use summary layers and memory layers separately.
2. Raw history remains untouched.

## Minimal Implementation Path

The first implementation slice should be:

1. Define interfaces and backend models.
2. Introduce SQLite-backed append-only conversation storage.
3. Adapt `AgentLoop` to depend on `ContextAssembler`.
4. Replace flattened history prompt assembly with message-list assembly.
5. Add internal FTS-backed history retrieval.

## Suggested First Files

New packages:

- `io.jobclaw.conversation`
- `io.jobclaw.context`
- `io.jobclaw.retrieval`
- `io.jobclaw.summary`

Suggested initial files:

- `ConversationStore.java`
- `StoredMessage.java`
- `SessionRecord.java`
- `MessageChunk.java`
- `ChunkSummary.java`
- `SessionSummaryRecord.java`
- `MemoryFact.java`
- `ContextAssembler.java`
- `ContextAssemblyOptions.java`
- `RetrievalService.java`
- `RetrievalBundle.java`
- `SearchQuery.java`
- `SummaryService.java`

## Immediate Next Step

Start with backend scaffolding only:

1. add the new interfaces and records
2. add a compatibility-focused storage adapter layer
3. wire no behavior changes yet
4. then refactor `AgentLoop` onto the new abstractions in a second step

## Implementation Status

### Landed

1. Append-only raw message storage is active via `ConversationStore`.
2. `SessionManager` now behaves primarily as a compatibility projection layer.
3. `AgentLoop` uses structured message-list context assembly instead of flattened history prompts.
4. Raw tool-call messages and tool-result messages are persisted in session history.
5. `chunk summaries`, `session summary`, and `memory facts` are persisted.
6. SQLite + FTS5 retrieval is wired for history, summary, and memory search.
7. `ContextAssembler` consumes recent history, retrieved history, summaries, and memory facts under token budgets.
8. Context policy is separated into `ContextAssemblyPolicy` and made configurable.
9. Existing web APIs remain compatible, including session list/detail and chat streaming.
10. Backend search API exists at `/api/sessions/search`.
11. Legacy `session.json` snapshots have been removed from the backend runtime path.

### Partially Landed

1. `ContextBuilder` has been reduced to system-prompt construction, but some legacy compatibility surface still remains for older call sites.
2. `SessionManager` still exposes a compatibility projection API, but no longer persists legacy session snapshots.
3. The search layer is currently SQLite + FTS5 only. Hybrid retrieval is not implemented yet.

### Not Done Yet

1. No vector retrieval or embedding-based ranking.
2. No dedicated background indexing worker. Index sync is currently lazy and query-triggered.
3. No frontend search UI or session-summary UI changes in this phase.
4. No full removal of legacy session JSON persistence.

## Current Risks

1. Query-triggered SQLite sync is operationally simple but can still introduce latency spikes on cold sessions.
2. Some classes still carry compatibility methods that are no longer part of the preferred path, so future cleanup should keep shrinking the legacy surface.

## Recommended Next Phase

1. Add one more end-to-end backend test covering `summary -> retrieval -> context assembly -> prompt message roles`.
2. If long-lived scale matters, move SQLite sync into an explicit indexer/background worker.
