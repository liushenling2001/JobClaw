# Runtime Memory, Skill, Tool, and Workflow Evolution Plan

## Goal

Move JobClaw from broad context injection to explicit, layered runtime selection:

- Memory stores user facts, preferences, and durable project rules.
- Workflow memory stores successful repeatable execution flows.
- Skills store mature, reusable capability packages.
- Tools are exposed by task type, not all at once.
- Workspace files store inputs, outputs, configuration, and artifacts.
- Learning/evolution produces candidates first; it does not directly rewrite core behavior.

This document is the implementation baseline for the next optimization phase.

## Current Problems

### Ambiguous memory behavior

JobClaw currently has several storage locations that can look like memory:

- `memory/MEMORIES.json`
- `memory/MEMORY.md`
- `memory/YYYYMM/YYYYMMDD.md`
- session summaries and `memory-facts.ndjson`
- ordinary workspace files
- bootstrap files such as `IDENTITY.md`, `SOUL.md`, `USER.md`, and sometimes `PROFILE.md`

This makes user intent unclear. When a user says "remember this", the system may only keep it in conversation history, later summarize it, write a daily note, or not persist it as durable memory at all.

### Full skill injection

`ContextBuilder` currently includes the installed skills summary in every system prompt. This increases prompt noise and can cause the model to call irrelevant skills.

### Full tool exposure

If an agent definition does not explicitly restrict tools, `AgentLoop` exposes all tool callbacks. This gives the model too many options for simple tasks and increases tool-selection errors.

### Successful flows are not reused

Repeated tasks, such as batch PDF review or Excel analysis report generation, are currently solved again from scratch unless the user writes a skill manually.

## Storage Boundaries

### Workspace files

Workspace files are for:

- input data
- generated reports
- exported files
- project configuration
- agent profile files
- skill files

Workspace files are not durable user memory unless explicitly imported or referenced.

### Memory

Memory stores durable facts and preferences:

- user preferences
- user facts
- project rules
- agent-specific long-term behavior notes

Primary store:

```text
<workspace>/memory/MEMORIES.json
```

Legacy compatibility:

```text
<workspace>/memory/MEMORY.md
```

### Workflow memory

Workflow memory stores successful repeatable execution flows:

```text
<workspace>/.jobclaw/workflows/recipes.json
```

Examples:

- batch PDF review flow
- Excel analysis report flow
- code modification and test flow

### Skills

Skills are mature reusable capability packages:

```text
<workspace>/skills/<skill-name>/SKILL.md
```

Workflow memory can later be promoted to a skill after repeated success and user approval.

### Checkpoints

Checkpoints store long-running task progress:

```text
<workspace>/.jobclaw/checkpoints/
```

They are runtime state, not user memory.

### Agent profiles

Role agents and user-defined agents are stored as configuration:

```text
<workspace>/.jobclaw/agents/*.json
```

They are not memory.

## Legacy File Positioning

### `IDENTITY.md`

Agent identity. It defines what JobClaw is. It is not user memory.

### `SOUL.md`

Agent values and behavioral principles. It is not user memory.

### `USER.md`

Human-readable user profile view. It can be generated from or synchronized with structured user memory, but it should not be the primary machine-readable store.

### `PROFILE.md`

Ambiguous legacy file. Do not treat it as a default memory source. If present, it should be migrated into one of:

- agent identity
- user memory
- project memory
- discarded legacy content

### `MEMORY.md`

Legacy long-term memory. Keep compatibility, but new explicit memory writes should go to `MEMORIES.json`.

## Target Runtime Layers

```text
User Request
  -> TaskPlanningPolicy
     -> TaskPlan
     -> DoneDefinition
     -> WorkflowMemorySearch
     -> SkillSelectionPolicy
     -> ToolSelectionPolicy
  -> ContextBuilder
     -> Identity Layer
     -> Relevant Memory Layer
     -> Relevant Workflow Layer
     -> Relevant Skill Layer
     -> Session Layer
     -> Legacy Layer
  -> AgentLoop
     -> selected tools only
  -> TaskCompletionController
     -> completion decision
     -> learning candidate
     -> workflow memory candidate
```

## Memory Optimization

### Add `memory` tool

Add a first-class memory tool:

```text
memory(action='remember', content='...', tags='...', scope='user|project|agent')
memory(action='search', query='...')
memory(action='list')
memory(action='forget', id='...')
```

Rules:

- If the user explicitly says "remember", "以后", "偏好", "规则", or "不要忘了", the agent must call `memory(action='remember')`.
- Memory writes should go to `MEMORIES.json`.
- Ordinary workspace files should not be used for durable user memory.

### ContextBuilder memory layering

`ContextBuilder` should clearly separate:

1. Identity layer
2. Selected user/project/agent memory
3. Selected workflow recipe
4. Selected skill summary
5. Session summary and retrieved memory facts
6. Legacy memory

## Workflow Memory

### Add workflow recipe model

Example:

```json
{
  "name": "批量 PDF 格式审查",
  "taskSignature": "batch_pdf_review",
  "applicability": "目录下多个 PDF，检查首页作者和末页参考文献",
  "planningMode": "WORKLIST",
  "deliveryType": "BATCH_RESULTS",
  "toolSequence": ["list_dir", "subtasks", "spawn", "read_pdf"],
  "subtaskPattern": "one-file-per-subtask",
  "requiredTools": ["list_dir", "subtasks", "spawn", "read_pdf"],
  "successCount": 2,
  "confidence": 0.6
}
```

### Recording rules

Record a workflow candidate when:

- the task completed successfully
- there was a clear tool sequence
- the task is repeatable
- the task is not purely conversational

### Usage rules

At task start:

- search relevant recipes
- inject the best high-confidence recipe as planning guidance
- do not force it if the current user request conflicts

### Promotion rules

After repeated success, suggest converting the workflow into a skill. Do not auto-create or modify a skill without approval.

## Skill Selection

Add `SkillSelectionPolicy`.

Inputs:

- user request
- planning mode
- delivery type
- agent profile
- matched workflow recipe

Behavior:

- default prompt only explains how to search/invoke skills
- inject only top relevant skill summaries
- full skill content is loaded with `skills(action='invoke')`

This replaces full installed-skill summary injection.

## Tool Selection

Add `ToolSelectionPolicy`.

Inputs:

- task plan
- done definition
- agent profile
- matched workflow recipe
- selected skills

Default toolsets:

```text
DIRECT_ANSWER
  memory, skills

DOCUMENT_ANALYSIS
  list_dir, read_file, read_pdf, read_word, read_excel, write_file

WORKLIST_BATCH
  list_dir, subtasks, spawn, read_file, read_pdf, read_word, read_excel

CODING
  list_dir, read_file, write_file, edit_file, run_command

AGENT_CONFIG
  agent_catalog, skills, spawn, memory

WORKFLOW_LEARNING
  memory, skills, agent_catalog
```

Agent profile `allowedTools` is an upper bound. Dynamic toolsets narrow the default set.

## Learning Candidates

Add `LearningCandidate`.

Candidate types:

- `MEMORY`
- `WORKFLOW`
- `NEGATIVE_LESSON`
- `SKILL_UPDATE`
- `AGENT_PROFILE_UPDATE`

Candidates are created from:

- failed tasks and blocked runs with attributable causes
- repeated successful flows after multiple similar uses
- explicit user corrections
- explicit user requests such as "固化为经验", "沉淀为经验", "记录为教训"

Single successful runs and ordinary "总结经验" statements are reference material, not experience candidates.
Relevant single-run workflow references may be injected at runtime as low-priority guidance.
Repeated similar successful workflows can be auto-promoted to formal workflow experience after the stability threshold is met.

Candidates should be reviewable. They should not automatically rewrite skills, profiles, or core logic.

## Implementation Order

### Phase 1: Explicit memory

- Add `MemoryTool`.
- Make explicit "remember" requests write to `MEMORIES.json`.
- Update `ContextBuilder` rules to distinguish memory from files.

Status: implemented.

### Phase 2: Context layering and skill selection

- Refactor `ContextBuilder` sections.
- Add `SkillSelectionPolicy`.
- Stop full installed-skill summary injection.

Status: implemented with conservative skill summary injection. Full skill content is still loaded through `skills(action='invoke')`.

### Phase 3: Workflow memory

- Add workflow recipe store and service.
- Record successful repeatable flows.
- Search and inject relevant workflow guidance at task start.

Status: implemented. Successful repeatable task runs are recorded under `.jobclaw/workflows/recipes.json` and high-confidence matches are injected as planning guidance.

### Phase 4: Dynamic tools

- Add `ToolSelectionPolicy`.
- Apply task-type toolsets in `AgentLoop`.
- Preserve agent profile `allowedTools` as upper bound.

Status: implemented conservatively. Explicit agent `allowedTools` bypass dynamic narrowing; otherwise the runtime selects tools by task type.

### Phase 5: Learning candidates

- Add candidate store.
- Record memory/workflow/skill/profile suggestions.
- Add front-end review later.

Status: backend candidate store implemented. Successful repeatable runs create pending `WORKFLOW` and `SKILL_UPDATE` candidates under `.jobclaw/learning/candidates.json`. Candidates do not automatically modify memory, skills, agent profiles, or core logic.

Failed or blocked harness runs create pending `NEGATIVE_LESSON` candidates. These candidates record failure reason, repair attempts, pending subtasks, and observed tool sequence so later similar tasks can avoid repeating the same failed path.

Backend review API:

```text
GET  /api/learning/candidates?status=pending|accepted|rejected
GET  /api/learning/candidates/{id}
POST /api/learning/candidates/{id}/accept
POST /api/learning/candidates/{id}/reject
```

`accept` and `reject` currently only update candidate status. They do not apply the candidate. Promotion into a workflow, skill, memory, or agent profile must remain a separate explicit action.

### Phase 6: Built-in experience review

- Add built-in `experience-review` skill as a compact operating protocol.
- Add onboard-only bootstrap for default directories and the daily internal cron job.
- Reuse the existing workspace cron store:

```text
<workspace>/cron/jobs.json
```

- Add one default internal job during `java -jar ... onboard`:

```text
id: builtin-experience-review-daily
type: INTERNAL
action: experience_review
schedule: 0 1 * * *
```

- Add an internal cron dispatcher so user message cron jobs and built-in maintenance jobs share the same cron runtime.
- Add `ExperienceReviewService` to read:

```text
<workspace>/.jobclaw/workflows/recipes.json
<workspace>/.jobclaw/learning/candidates.json
```

- Write daily evidence reports to:

```text
<workspace>/.jobclaw/experience/experience-review-YYYY-MM-DD.md
<workspace>/.jobclaw/experience/latest.md
```

Status: implemented as a conservative summarizer. It writes a local evidence report first, then can optionally call the configured LLM once to refine the evidence into concise operational guidance.

The report separates:

- reusable successful workflows
- pending negative lessons
- other pending learning candidates

Implementation boundary:

- The built-in skill defines the review method and output contract.
- The actual scheduled runtime remains Java code inside JobClaw.
- Onboard initializes cron config; runtime constructors do not silently create default jobs.
- Daily review produces evidence for user review and future promotion, not automatic self-modification.
- LLM refinement is bounded and advisory only; failure falls back to the local evidence report.

LLM review configuration:

```json
{
  "experience": {
    "llmReviewEnabled": true,
    "llmReviewMaxInputChars": 12000,
    "llmReviewMaxTokens": 800,
    "llmReviewMinPendingCandidates": 1
  }
}
```

LLM call boundary:

- no LLM call during every task step
- no LLM call during every tool call
- no LLM call during automatic experience guidance
- one optional LLM call during daily experience review when pending candidates exist
- output is appended under `LLM Refined Insights`

### Phase 7: Automatic experience guidance

- Add `ExperienceGuidanceService`.
- At task start, build one compact guidance block from:

```text
accepted experience memory
pending or accepted NEGATIVE_LESSON candidates
relevant successful workflow memory
```

- Inject guidance before the user request through the orchestrator.
- User-accepted experience memory is placed before pending negative lessons and successful workflows.
- Rejected negative lessons are ignored.
- Experience guidance is advisory only:

```text
user instruction > accepted experience memory > negative lesson avoidance > successful workflow guidance
```

Status: implemented. The runtime can now automatically warn itself about similar failed paths and reuse successful workflows without modifying skills, memory, agent profiles, or core logic.

### Phase 8: Accepted experience memory

- Add `ExperienceMemory`.
- Store user-accepted experience in:

```text
<workspace>/.jobclaw/experience/accepted-experience.json
```

- Candidate acceptance behavior:

```text
accept NEGATIVE_LESSON -> ExperienceMemory(type=AVOID_RULE)
accept WORKFLOW -> ExperienceMemory(type=WORKFLOW_EXPERIENCE)
accept SKILL_UPDATE -> ExperienceMemory(type=WORKFLOW_EXPERIENCE)
```

- `SKILL_UPDATE` no longer means automatically creating a skill.
- Accepted experience remains queryable and automatically usable by `ExperienceGuidanceService`.
- Skills remain reserved for mature, explicit, user-approved capability packages.

Status: implemented. Accepting a candidate now marks it accepted and writes/updates accepted experience memory. The daily experience report lists accepted experience separately.

## Expected Outcome

After this upgrade:

- "Remember this" has one reliable path into structured memory.
- Repeated task flows become reusable without manual skill creation.
- Skills are retrieved when useful instead of injected in full.
- Tools are selected by task type instead of fully exposed.
- Workspace files remain project artifacts and data.
- Long-running task progress remains checkpoint state, not memory.
