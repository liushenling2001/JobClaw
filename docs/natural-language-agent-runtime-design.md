# Natural-Language Agent Runtime Design

## Goal

Allow users to create, invoke, and observe specialized agents through natural language instead of manual UI forms.

Examples:

- "帮我创建一个专门做 JD 分析的智能体"
- "使用 JD 分析师，给我拆解这个岗位"
- "让招聘研究员后台调研这家公司，并持续反馈进展"

## Product Requirements

1. Natural-language agent creation
2. Natural-language agent invocation
3. Persistent reusable agent catalog
4. Async observed execution
5. Parent agent can observe child execution state
6. Frontend can display progress without mixing it into normal chat messages

## Current Baseline

Existing project capabilities:

- `AgentDefinition` already models role prompt, tool restrictions, skills, and agent-specific config.
- `AgentLoop.processWithDefinition(...)` already supports execution by custom definition.
- `SpawnTool` already supports sync and async sub-agent execution.
- `ExecutionTraceService` already streams execution events to SSE subscribers.

Current gaps:

- No persistent agent catalog
- No natural-language intent routing for create/invoke/update agent
- No stable `runId` / `parentRunId` model
- Async events are still primarily session-scoped, which can mix foreground chat and background work
- Parent agent cannot reliably observe child execution as a first-class runtime concept

## Target Architecture

### 1. Agent Catalog

Persistent reusable agent assets.

Core fields:

- `agent_id`
- `code`
- `display_name`
- `description`
- `system_prompt`
- `allowed_tools_json`
- `allowed_skills_json`
- `model_config_json`
- `memory_scope`
- `status`
- `visibility`
- `created_at`
- `updated_at`
- `version`

Alias table:

- `alias_id`
- `agent_id`
- `alias`

### 2. Intent Routing

Add an `AgentIntentRouter` that classifies incoming user requests into:

- `DEFAULT_CHAT`
- `CREATE_AGENT`
- `UPDATE_AGENT`
- `INVOKE_AGENT`
- `LIST_AGENTS`
- `MULTI_AGENT_PROJECT`

This must replace continued expansion of regex-only routing in `AgentOrchestrator`.

### 3. Invocation Runtime

Add `AgentInvocationService` and `AgentRunService`.

Responsibilities:

- Resolve agent by natural-language name or alias
- Create a run record
- Start sync or async execution
- Attach run metadata to execution events
- Track parent-child relationships between runs

### 4. Observability Layer

Execution events must be run-scoped, not only session-scoped.

Required event fields:

- `sessionId`
- `runId`
- `parentRunId`
- `agentId`
- `agentName`
- `type`
- `content`
- `metadata`
- `timestamp`

### 5. Parent-Agent Observation

Child agents should emit raw execution events.

An `AgentRunObserver` should summarize raw child activity into parent-consumable progress signals:

- started
- stage progress
- warning
- failed
- completed

The parent agent should consume summarized progress rather than raw token streams.

## Frontend Contract

The frontend should continue using the chat session as the main interaction surface, but background tasks must render as run cards instead of ordinary chat bubbles.

Minimal run card fields:

- `runId`
- `agentName`
- `status`
- `latestProgress`
- `startedAt`
- `finishedAt`

SSE payloads must always include `runId`.

Frontend rendering rules:

- normal user/assistant dialogue stays in the chat timeline
- background progress updates are grouped by `runId`
- multiple async child runs can coexist in one session without interleaving message bubbles

## Storage Design

### `agents`

- persistent specialized agent definitions

### `agent_aliases`

- alias and natural-language trigger phrases

### `agent_runs`

- one record per execution run

Suggested fields:

- `run_id`
- `parent_run_id`
- `session_id`
- `agent_id`
- `agent_name`
- `run_type`
- `status`
- `user_request`
- `started_at`
- `updated_at`
- `finished_at`

### `agent_run_events`

- durable event log for observed runs

Suggested fields:

- `event_id`
- `run_id`
- `session_id`
- `agent_id`
- `type`
- `content`
- `metadata_json`
- `created_at`

## Recommended Java Interfaces

- `AgentCatalogStore`
- `AgentCatalogService`
- `AgentResolver`
- `AgentIntentRouter`
- `AgentInvocationService`
- `AgentRunService`
- `AgentRunObserver`

## Execution Modes

### Sync

Foreground execution that blocks until result returns.

### Async Observed

Background child execution with:

- immediate acknowledgment to user
- progress events to frontend
- summarized state updates available to parent agent

### Team Async Observed

One parent instruction starts a project agent, which internally orchestrates multiple specialized agents while exposing a single run tree to the frontend.

## Minimum Delivery Plan

### Phase 1

- add `AgentCatalog` persistence
- add `runId` / `parentRunId` execution model
- extend SSE event payloads
- wire async spawn into run-scoped observation

### Phase 2

- add `AgentIntentRouter`
- add natural-language create/invoke flow
- add resolver by alias and trigger phrase

### Phase 3

- add parent-side progress summarization
- add project-agent orchestration with run trees
- add frontend run cards

## Compatibility Strategy

- Keep existing chat APIs working
- Keep session-based SSE subscription working
- Extend SSE payloads rather than replacing them
- Keep `AgentLoop` as execution kernel for now
- Gradually reduce `AgentOrchestrator` regex routing and `AgentRegistry` pseudo-instance semantics

## Immediate Implementation Scope

This round will implement:

1. design doc
2. persistent `AgentCatalog`
3. run-scoped execution metadata
4. async spawn event forwarding with parent-child run linkage

Natural-language intent parsing and frontend run cards remain next-step work.
