---
name: batch-file-task-runner
description: 通用批量文件长任务执行骨架。用于没有专用 skill 时，对文件夹内多份 PDF、Word、Excel、TXT/MD 文档逐个执行用户给定检查、抽取、判断或处理要求，并通过 manifest executionMode=managed 以短上下文逐项运行，最后生成 JSONL 与汇总报告。不要用于已有更专用 skill 的任务。
---

# Batch File Task Runner

目标：在没有专用 skill 时，把“文件夹内多文件长任务”拆成 managed manifest item loop，避免普通主流程背完整执行轨迹。

最终完成条件：`汇总报告存在且非空`。默认产物为 JSONL 中间结果和 Markdown 报告；如果用户要求 Excel，可在 final 阶段调用合适的 xlsx skill。

## 使用边界

只在用户明确要求批量处理文件夹/多文件，且没有更专用 skill 时使用。

如果存在更专用 skill，优先使用专用 skill。例如匿名盲审格式检查应使用 `batch-document-anonymity-format-check`，批量字段抽取到 Excel 应使用 `batch-document-extract-excel`。

不要让框架自动猜业务规则。每个文件要执行的检查/抽取/判断规则必须来自用户原始任务。

## 必要输入

- `input_dir`：待处理文件夹。
- `task_instruction`：用户要求对每个文件执行的具体任务。
- `output_format`：默认 `markdown`，可为 `jsonl`、`xlsx`、`markdown`。

缺少 `task_instruction` 或必要业务规则时，调用 `user_input` 询问，不要自行发明规则。

## 小模型硬执行卡

1. `batch-file-task-runner` 是 skill 名，不是工具名。
2. 第一个实际动作必须是：
   ```text
   list_dir(path='<input_dir>')
   ```
3. 没有当前 run 的 `list_dir` 结果，禁止调用 `manifest.create`。
4. `manifest.create.items` 只能来自本次 `list_dir` 返回的真实文件名或相对路径。
5. `manifest.create` 必须包含 `executionMode='managed'`。
6. 创建 managed manifest 后不要自己循环处理文件，框架会接管 item loop。
7. 每个 item 只处理一个文件，只返回一个 JSON 对象。
8. 不要使用 `spawn`、`collaborate`。

## 工具

使用这些 JobClaw 工具：

- `list_dir`
- `read_pdf`
- `read_word`
- `read_excel`
- `read_file`
- `manifest`
- `context_ref`
- `user_input`
- `write_file`，仅 final 阶段写 Markdown 报告时使用
- `skills(action='invoke', name='minimax-xlsx')`，仅用户要求 Excel 且该 skill 可用时使用

不要用 `run_command` / `exec` 读取或解析文档，除非用户明确要求调试 skill 或系统没有可用读取工具。

## Manifest 创建

列出 `input_dir` 后，只处理：

- `.pdf`
- `.doc`
- `.docx`
- `.xls`
- `.xlsx`
- `.txt`
- `.md`

忽略已生成目录、报告、中间文件、临时文件和隐藏文件。

创建 manifest：

```text
manifest(
  action='create',
  taskKey='skill:batch-file-task-runner|inputDir=<input_dir>',
  items='[{"id":"文件A.pdf","title":"文件A.pdf"},{"id":"子目录/文件B.docx","title":"子目录/文件B.docx"}]',
  schema='{"columns":["文件名","文件路径","状态","结果摘要","问题列表","证据","错误"]}',
  artifactPath='<input_dir>\批量任务结果.intermediate.jsonl',
  executionMode='managed',
  finalArtifactPath='<input_dir>\批量任务报告.md',
  finalArtifactType='markdown'
)
```

硬规则：

- `id` 必须是真实文件名或相对路径。
- 禁止 `item-001`、`file-1` 这类虚构 id。
- `artifactPath` 必须是 `.jsonl`，不能是最终报告路径。
- 如果返回已有 manifest，继续用已有 `manifestId`，不要重建。

## Managed Runtime

mode: runner
parallelism: 2
frameworkWrites: item-json,jsonl,manifest
resultSink: both
aggregateSink: jsonl
itemResultPathTemplate: {{task.inputDir}}\批量任务结果.items\{{item.safeId}}.json
aggregatePathTemplate: {{artifactPath}}
itemOutput: json_object
allowedTools: read_pdf, read_word, read_excel, read_file, context_ref

框架每轮只给当前 LLM 一个 item，并负责把该 item 标记为 running。
当前 LLM 只负责当前文件，不负责选择下一项、不负责更新 manifest、不负责最终汇总。

### Item Loop

当前任务：batch-file-task-runner

当前只处理这个 item：

- itemId: `{{item.id}}`
- 文件路径或名称: `{{item.path}}`
- schema: `{{schema}}`

本轮执行：

1. 确定 `source_path = input_dir + "\\" + itemId`。
2. 按扩展名读取当前文件。
3. 只按用户原始任务要求处理当前文件。
4. 如果读取结果很大，优先使用 `context_ref(search|summary)` 或小范围 `read`。
5. 只返回一个 JSON 对象，不要输出 Markdown，不要解释流程。

返回 JSON 必须包含：

```json
{
  "文件名": "",
  "文件路径": "",
  "状态": "success",
  "结果摘要": "",
  "问题列表": [],
  "证据": [],
  "错误": ""
}
```

读取失败时也返回 JSON：

- `状态 = failed`
- `错误` 写明原因

禁止：

- 不要处理其它 item。
- 不要生成最终报告。
- 不要重新 `manifest.create`。
- 不要调用 `manifest.start`、`manifest.done`、`manifest.fail`。

### Finalize

manifest item 循环结束后，只做汇总报告。

执行：

1. 确认 `pending=0` 且 `running=0`。
2. 检查 `{{intermediateArtifactPath}}` 是否存在且非空。
3. 如 JSONL 缺失或疑似污染，只从 `批量任务结果.items` 的单项 JSON 重建，不要重读源文档。
4. 用户要求 Excel 时，调用 `minimax-xlsx` 把 JSONL 转为 Excel。
5. 默认生成 Markdown 汇总报告，报告只总结文件数、失败项、问题清单和输出路径。
6. 最终回复只写：
   - 报告路径
   - JSONL 路径
   - total / done / failed

## Artifact Completion

requiresArtifact: true
artifactType: markdown
artifactPathTemplate: {{finalArtifactPath}}
disableGenericArtifactGuard: false
