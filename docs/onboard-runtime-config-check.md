# Onboard Runtime Configuration Check

## Scope

This note documents the expected runtime state after first `java -jar jobclaw.jar onboard`.

## Onboard Creates

Workspace directories:

```text
<workspace>/
<workspace>/memory/
<workspace>/skills/
<workspace>/sessions/
<workspace>/sessions/conversation/
<workspace>/cron/
<workspace>/.jobclaw/
<workspace>/.jobclaw/agents/
<workspace>/.jobclaw/checkpoints/
<workspace>/.jobclaw/experience/
<workspace>/.jobclaw/learning/
<workspace>/.jobclaw/workflows/
```

Default runtime files:

```text
~/.jobclaw/config.json
<workspace>/AGENTS.md
<workspace>/SOUL.md
<workspace>/USER.md
<workspace>/IDENTITY.md
<workspace>/PROFILE.md
<workspace>/memory/MEMORY.md
<workspace>/memory/HEARTBEAT.md
<workspace>/cron/jobs.json
<workspace>/.jobclaw/bootstrap-state.json
```

Default cron job:

```text
id: builtin-experience-review-daily
type: INTERNAL
action: experience_review
schedule: 0 1 * * *
```

## Main Agent Configuration

The main agent is loaded from:

```text
~/.jobclaw/config.json
```

Critical fields:

```json
{
  "agent": {
    "workspace": "~/.jobclaw/workspace",
    "provider": "dashscope",
    "model": "qwen3.5-plus",
    "maxTokens": 16384,
    "temperature": 0.7,
    "maxToolIterations": 20,
    "toolCallTimeoutSeconds": 300,
    "subtaskTimeoutMs": 900000
  }
}
```

Remote providers require `providers.<provider>.apiKey`.

`ollama` is allowed without `apiKey` when `baseUrl` is configured.

## Sub-Agent Configuration

Sub-agents can configure:

```json
{
  "modelConfig": {
    "provider": "openai",
    "model": "gpt-5.4-mini",
    "apiBase": "https://api.openai.com/v1",
    "temperature": 0.2,
    "maxTokens": 8192,
    "timeoutMs": 900000
  }
}
```

Sub-agents do not store `apiKey`.

Runtime resolution:

```text
sub-agent provider/model/apiBase
  -> provider name selects providers.<provider>
  -> apiKey is read from main config providers.<provider>.apiKey
  -> model comes from sub-agent modelConfig.model if present
  -> missing sub-agent modelConfig values are filled from main agent config
```

This allows different models/providers per sub-agent while keeping secrets in one place.

## Failure Behavior

If a remote provider is selected but its `apiKey` is missing, runtime fails with a clear configuration error instead of silently falling back.

If a provider name is unknown, runtime may fall back to the first valid configured provider.

If no valid provider exists, startup/execution fails with `No valid provider configuration found`.

## Runtime Chain

Frontend chat and channel execution should enter the orchestrator chain:

```text
Web/API or channel
  -> AgentOrchestrator
  -> TaskPlanningPolicy
  -> ExperienceGuidanceService
  -> AgentLoop
  -> TaskCompletionController
  -> Learning/Workflow/Experience recording
```

`subtasks` requires an active task harness run. It works when execution enters through `AgentOrchestrator`.

## Verified

Covered by tests:

```text
ConfigDefaultsTest
SystemBootstrapServiceTest
ProviderRuntimeTest
AgentProfileServiceTest
```
