---
name: experience-review
description: Review JobClaw run history and produce compact lesson, memory, operating-guidance, and skill candidates.
---

# Experience Review

Use this built-in skill for scheduled JobClaw experience consolidation.

## Purpose

Analyze compact run snapshots and identify:

- reusable successful operating guidance
- failed patterns that should be avoided
- memory candidates from repeated user corrections
- promotion candidates for skills or agent profiles

This skill defines the review method and output contract. JobClaw runtime owns scheduling, data collection, persistence, and injection.

## Hard Rules

- Do not modify files, skills, agent profiles, memory, or core logic.
- Return candidates only.
- Every lesson or candidate must include evidence run ids.
- Do not promote a single ambiguous failure into a hard rule.
- Merge similar findings instead of producing many near-duplicates.
- Keep each `avoid` and `prefer` statement short and actionable.
- Prefer negative lessons only when there is repeated failure, explicit user correction, or clear completion/runtime evidence.
- Do not recommend injecting more than three lessons into future task context.

## Input Contract

The runtime should provide JSON with:

```json
{
  "reviewWindow": {"from": "...", "to": "..."},
  "runs": [],
  "existingExperience": [],
  "existingLessons": []
}
```

Each run should be compact. Do not pass full file contents or long model responses.

## Output Contract

Return strict JSON:

```json
{
  "negativeLessons": [
    {
      "taskPattern": "batch_pdf_review",
      "avoid": "Do not load all PDFs into the parent context.",
      "prefer": "Keep per-item outputs in artifacts and keep only aggregate progress in model context.",
      "evidenceRunIds": ["run-123"],
      "confidence": 0.72,
      "reason": "Parent context overflow caused incomplete execution."
    }
  ],
  "positiveGuidance": [
    {
      "taskPattern": "excel_report_generation",
      "prefer": "Read workbook structure, generate a script, then write the report.",
      "toolSequence": ["read_excel", "run_command", "write_file"],
      "evidenceRunIds": ["run-456"],
      "confidence": 0.66,
      "reason": "Stable successful operating pattern with reusable tool sequence."
    }
  ],
  "promotionCandidates": [
    {
      "type": "SKILL_UPDATE",
      "title": "Excel report generation guidance",
      "reason": "Repeated successful operating pattern with stable structure.",
      "confidence": 0.58,
      "evidenceRunIds": ["run-456"]
    }
  ],
  "memoryCandidates": [
    {
      "scope": "project",
      "content": "For batch document review, persist per-file results as artifacts and keep only aggregate progress in context.",
      "confidence": 0.64,
      "evidenceRunIds": ["run-123", "run-124"]
    }
  ]
}
```

If there is no useful evidence, return empty arrays.
