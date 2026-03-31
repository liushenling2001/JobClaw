# Multi-Agent Shared Board Design

## Goal

Upgrade multi-agent collaboration from simple result aggregation to board-backed coordination.

Phase 1 focuses on:

1. `SharedBoardService`
2. `board_write`
3. `board_read`
4. `TEAM` mode integration with a shared board

This phase does not yet implement:

- real-time agent-to-agent messaging
- event-driven subscriptions between agents
- automatic re-planning based on board changes

## Problem

The current multi-agent runtime can parallelize work, but it still behaves like:

- agent A runs independently
- agent B runs independently
- results are collected at the end
- one summarizer produces the final answer

That means the system lacks:

- a shared workspace for structured intermediate artifacts
- durable collaboration state across the team run
- a clean substrate for later event-driven coordination

## Target Model

### Core entities

#### Board

A collaboration board belongs to one collaboration run.

Suggested fields:

- `board_id`
- `run_id`
- `title`
- `created_at`

#### Board entry

Structured records written by agents or the system.

Suggested fields:

- `entry_id`
- `board_id`
- `entry_type`
- `title`
- `content`
- `author_agent_id`
- `author_agent_name`
- `visibility`
- `created_at`

Recommended `entry_type` values:

- `task`
- `fact`
- `artifact`
- `decision`
- `risk`
- `question`
- `summary`

## Phase 1 Architecture

### SharedBoardService

Responsibilities:

- create boards for collaboration runs
- persist board metadata
- append entries safely
- read recent entries

Initial implementation choice:

- file-based storage under `workspace/sessions/conversation/boards`

Rationale:

- simple to inspect
- low migration cost
- enough for first-stage coordination

### Tool surface

#### `board_write`

Purpose:

- let an agent publish intermediate work to the shared board

Inputs:

- `boardId`
- `entryType`
- `title`
- `content`
- `visibility`

The tool fills `author_agent_id` and `author_agent_name` from execution context.
It also emits a `CUSTOM` execution event with board metadata (`boardId`, `entryId`, `entryType`) so parent observers and SSE clients can track progress.

#### `board_read`

Purpose:

- let an agent fetch recent board context

Inputs:

- `boardId`
- `limit`

Output:

- recent formatted board entries

## Collaboration mode integration

For `collaborate(mode="TEAM")`:

1. create a board at the start of the collaboration run
2. write the root task to the board
3. pass the board id into each child agent task
4. instruct child agents to use `board_read` and `board_write`
5. write each agent result to the board even if the agent itself did not call `board_write`
6. build the summarizer input from board contents
7. write the final summary back to the board
8. emit board-related execution events during board creation and board writes

For `collaborate(mode="SEQUENTIAL")`:

1. create a board at run start
2. write the root task to the board
3. include board id in each step agent task
4. write each step output to the board as artifacts
5. write step failures as risk entries
6. emit board-related execution events during board creation and board writes

For `collaborate(mode="DEBATE")`:

1. create a board at run start
2. write debate topic to the board
3. write each round side output to the board
4. write round errors as risk entries
5. generate final summary from board entries and write summary back to board
6. emit board-related execution events during board creation and board writes

This produces:

- one durable collaboration trace per team run
- structured intermediate artifacts
- a stable upgrade path toward later event-driven coordination

## Current implementation status

Implemented in this round:

- `SharedBoardService`
- file-backed board storage
- `board_write`
- `board_read`
- `TEAM` mode board-backed flow
- `SEQUENTIAL` mode board-backed flow
- `DEBATE` mode board-backed flow
- root task/topic written to board
- agent outputs written to board as artifacts
- failures written to board as risks
- final summaries written to board
- summarizers read board-backed context
- board writes emit execution events for observer-friendly progress

Not yet implemented:

- board entry search
- subscriptions on board changes
- per-entry conflict resolution
- board-aware re-planning
- master-only board visibility enforcement

## Next recommended steps

### Phase 2

- add board-aware event routing
- emit structured board events when new entries are written
- let the parent agent observe board updates as progress summaries

Current status:

- board writes now emit summarized progress events
- event metadata includes `boardId`, `entryId`, and aggregated counters
- `ExecutionTraceService` supports filtering history by `runId` and by `boardId`

### Phase 3

- add coordinator logic that re-plans based on board state
- add run-tree plus board-tree observability
