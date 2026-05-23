---
name: office-parser
description: Parse office and document files outside the JobClaw core runtime. Use when a task needs text extraction from PDF, Word, Excel, PowerPoint, RTF, email, archives, or other rich document formats. Prefer core read_file for plain text, Markdown, JSON, YAML, CSV, logs, and source code.
---

# Office Parser

This skill keeps heavyweight document parsing out of the CLI core distribution.

Use this skill when the user asks to read or summarize:

- PDF files
- Word documents: `.doc`, `.docx`
- Excel workbooks: `.xls`, `.xlsx`
- PowerPoint decks: `.ppt`, `.pptx`
- RTF, email, archives, image metadata, or other rich document formats

Do not use this skill for normal text/code files. Use `read_file` for those.

## Entrypoint

Run:

```bash
scripts/parse-document.sh <path>
```

Optional page sampling arguments are accepted and passed through to compatible parsers:

```bash
scripts/parse-document.sh <path> --front-pages 3 --random-pages 2 --tail-pages 2
```

## Runtime

The preferred runtime is Apache Tika App as an external sidecar jar. Set one of:

```bash
export JOBCLAW_TIKA_APP_JAR=/path/to/tika-app.jar
```

or place it at:

```bash
~/.jobclaw/skills/office-parser/lib/tika-app.jar
```

If the Tika sidecar is unavailable, the script falls back to a small built-in Python extractor for `.docx`, `.xlsx`, `.csv`, `.txt`, `.md`, `.json`, `.yaml`, `.yml`, `.xml`, `.html`, and `.htm`.

## Output Contract

Return extracted UTF-8 text to stdout. On failure, print a clear error to stderr and exit non-zero.
