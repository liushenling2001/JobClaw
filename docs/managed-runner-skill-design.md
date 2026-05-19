# Managed Runner Skill 设计说明

这份文档给 skill 作者看。目标是把复杂批量任务写清楚，让小模型也能稳定执行，同时不把 JobClaw 框架变成某一个任务的专用流程。

核心边界只有一句话：

> skill 定义流程和契约；模型负责单个对象的语义判断；框架只负责推进对象、保存中间结果、更新 manifest 状态，并在全部对象结束后把控制权交还给主模型。

框架不能替模型理解业务，也不能硬编码 Excel、报告、论文、PDF 这类具体任务。最终产物怎么生成，由 skill 的 Finalize 定义。

## 什么时候使用

只有同时满足这些条件，才应该写 `mode: runner`：

- 任务有多个独立对象，例如多个文件、多个记录、多个 URL。
- 每个对象可以用同一张“执行卡”处理。
- 单个对象处理失败时，可以记录失败并继续下一个对象。
- 全部对象完成后，才能生成最终产物。

不要把普通问答、单文件编辑、强依赖的多阶段推理、需要模型自由探索的任务写成 managed runner。

## 总流程

1. skill 先告诉模型如何发现真实对象，例如调用 `list_dir`。
2. 模型拿到真实对象列表后，调用 `manifest.create`。
3. 只有当 manifest 使用 `executionMode=managed`，并且当前 skill 声明了 `mode: runner`，框架才接管 item loop。
4. 接管期间，主模型不再自由循环规划。
5. 框架每次只交给模型一个 item 的执行卡。
6. 模型只返回当前 item 的结果。
7. 框架保存结果、维护中间聚合、更新 manifest done 或 failed。
8. 所有 item 结束后，框架把状态、结果位置、下一步要求交还给主模型。
9. 主模型按照 skill 的 Finalize 生成最终产物。

## 职责边界

skill 负责：

- 定义如何发现真实 item。
- 定义 `taskKey` 的命名规则。
- 定义 item 的输入字段和输出格式。
- 定义允许使用的读取工具。
- 定义中间结果保存方式。
- 定义最终产物如何生成。

模型负责：

- 调用工具读取当前 item。
- 根据当前 item 内容做语义抽取、分析、判断。
- 返回当前 item 的结果。

框架负责：

- 按 manifest 推进 pending item。
- 保存每个 item 的原始模型输出到 `context_ref`。
- 按 skill 配置写单项文件、聚合文件。
- 更新 manifest item 状态。
- 单个 item 失败时继续后续 item。
- 全部结束后交还主模型。

框架不负责：

- 不判断业务字段是否正确。
- 不决定最终产物类型。
- 不自动生成 Excel、报告、PPT。
- 不在 direct 路径猜测是否要接管任务。
- 不替 skill 增加隐藏流程。

## Managed Runtime 模板

skill 中建议明确写出这一段：

```text
## Managed Runtime

mode: runner
parallelism: 1
frameworkWrites: manifest,refs
resultSink: context_ref
aggregateSink: none
itemOutput: markdown
allowedTools: read_file, context_ref

### Item Loop
只处理当前 item。读取当前 item 后，返回一段 Markdown 笔记。

### Finalize
所有 item 完成后，根据 manifest refs 和中间结果生成最终产物。
```

### 字段说明

- `mode: runner`
  - 声明这个 skill 支持框架接管 item loop。
  - 只有模型创建 `executionMode=managed` 的 manifest 后才生效。

- `parallelism`
  - 当前稳定值是 `1`。
  - 后续如支持真正并发，也应该由 skill 显式声明。

- `frameworkWrites`
  - 给人看的职责说明。
  - 真正决定写什么的是 `resultSink` 和 `aggregateSink`。

- `itemOutput`
  - 单个 item 的返回形态。
  - 支持：`json_object`、`text`、`markdown`、`file_path`。

- `resultSink`
  - 单个 item 结果保存位置。
  - 支持：`context_ref`、`item_file`、`both`。

- `aggregateSink`
  - 批量中间聚合方式。
  - 支持：`jsonl`、`json_array`、`markdown`、`none`。

- `itemResultPathTemplate`
  - 单项文件路径模板。
  - 只有 `resultSink=item_file` 或 `resultSink=both` 时才需要。

- `aggregatePathTemplate`
  - 聚合文件路径模板。
  - 只有 `aggregateSink` 不是 `none` 时才需要。

- `allowedTools`
  - item loop 期间允许模型使用的工具。
  - 建议只放读取类工具和 `context_ref`。
  - 不要把 `skills`、`manifest`、`write_file`、`append_file` 放进去，除非这个 skill 明确需要。

## resultSink 怎么选

`context_ref`

- 框架只保存模型原始输出引用。
- 不强制生成单项文件。
- 适合综述笔记、审查意见、证据片段、自由文本分析。

```text
resultSink: context_ref
itemOutput: markdown
```

`item_file`

- 框架把单个 item 结果写入文件。
- 适合需要断点续跑、人工检查、后续工具读取的流程。

```text
resultSink: item_file
itemResultPathTemplate: {{task.inputDir}}\results.items\{{item.safeId}}.json
itemOutput: json_object
```

`both`

- 同时保存 `context_ref` 和单项文件。
- 适合批量抽取类任务。原始输出可追踪，单项文件可恢复。

```text
resultSink: both
itemResultPathTemplate: {{task.inputDir}}\抽取结果.items\{{item.safeId}}.json
itemOutput: json_object
```

如果没有写 `resultSink`：

- 有 `itemResultPathTemplate` 时默认 `both`。
- 没有 `itemResultPathTemplate` 时默认 `context_ref`。

## aggregateSink 怎么选

`jsonl`

- 每个 item 追加一行 JSON。
- 适合最终转 Excel、CSV、数据库导入。

```text
aggregateSink: jsonl
aggregatePathTemplate: {{artifactPath}}
```

`json_array`

- 维护一个 JSON 数组文件。
- 适合后续工具要求完整数组输入。

```text
aggregateSink: json_array
aggregatePathTemplate: {{task.outputDir}}\items.json
```

`markdown`

- 每个 item 追加一个 Markdown 小节。
- 适合综述、读书笔记、调研报告材料。

```text
aggregateSink: markdown
aggregatePathTemplate: {{task.outputDir}}\review-notes.md
```

`none`

- 不写聚合文件。
- 只依赖 manifest 状态和 item refs。

```text
aggregateSink: none
```

如果没有写 `aggregateSink`：

- 有 `aggregatePathTemplate` 时默认 `jsonl`。
- 没有 `aggregatePathTemplate` 时默认 `none`。

## manifest.create 应该怎么写

managed runner 不是框架猜出来的，必须由模型根据 skill 显式创建 manifest。

skill 应该告诉模型：

- 先读取真实输入列表。
- 不要使用示例 item。
- 不要用 `文件A.pdf`、`item-001` 这类占位对象。
- `items` 必须对应真实任务对象。
- `taskKey` 由 skill 定义规则，用于同一会话内幂等创建。
- 重复 create 同一个 `taskKey` 时，应合并缺失 item，而不是重建一套任务。
- `artifactPath` 是中间聚合产物路径。
- `finalArtifactPath` 是最终产物路径。
- `executionMode=managed` 只表示框架接管 item loop，不表示框架自动生成最终产物。

## Item Loop 怎么写

Item Loop 要像一张执行卡，不要像一篇教程。

建议包含：

- 当前 item 的路径或标识。
- 用户要求的字段或输出 schema。
- 允许使用哪些读取工具。
- 只处理当前 item。
- 不重建 manifest。
- 不生成最终产物。
- 不写中间文件，除非 `allowedTools` 明确允许。
- 返回格式必须等于 `itemOutput`。

示例：

```text
### Item Loop
你只处理当前 item。

当前 item:
- id: {{item.id}}
- path: {{item.path}}

输出 schema:
{{schema}}

要求:
- 使用 allowedTools 中的读取工具读取当前文件。
- 只返回一个 JSON object。
- 不要调用 manifest。
- 不要调用 skills。
- 不要写文件。
- 不要生成最终 Excel。
- 如果字段无法识别，填“未识别”。
```

## Finalize 怎么写

Finalize 是全部 item 完成后的下一步，不是 item loop 的一部分。

Finalize 应该说明：

- 当前 manifest 已结束。
- 中间结果在哪里。
- 是否存在失败 item。
- 最终产物类型是什么。
- 用哪个工具或 skill 生成最终产物。
- 不要重新读取全部源文件。
- 不要重新创建 manifest。
- 不要从头执行 item loop。

示例：

```text
### Finalize
当 manifest pending=0 且 running=0 后，使用 aggregatePathTemplate 指向的 JSONL 生成最终 Excel。
不要重新读取源文件。
不要重新创建 manifest。
最终回复必须包含 Excel 路径、总 item 数、成功数、失败数。
```

## 三个完整例子

### 1. 批量抽取到 Excel

```text
## Managed Runtime

mode: runner
parallelism: 1
frameworkWrites: item-json,jsonl,manifest
resultSink: both
aggregateSink: jsonl
itemResultPathTemplate: {{task.inputDir}}\抽取结果.items\{{item.safeId}}.json
aggregatePathTemplate: {{artifactPath}}
itemOutput: json_object
allowedTools: read_pdf, read_word, read_file, context_ref

### Item Loop
读取当前文档，只返回一个 JSON object，字段必须来自用户 schema。

### Finalize
使用 JSONL 生成最终 Excel。不要重新读取源文件。
```

### 2. 论文综述笔记

```text
## Managed Runtime

mode: runner
parallelism: 1
frameworkWrites: refs,markdown,manifest
resultSink: context_ref
aggregateSink: markdown
aggregatePathTemplate: {{task.outputDir}}\论文笔记.intermediate.md
itemOutput: markdown
allowedTools: read_pdf, read_word, read_file, context_ref

### Item Loop
读取当前论文，返回 Markdown 笔记，包含研究问题、方法、结论、可引用观点。

### Finalize
基于 Markdown 中间笔记撰写综述报告。
```

### 3. 批量校验

```text
## Managed Runtime

mode: runner
parallelism: 1
frameworkWrites: refs,manifest
resultSink: context_ref
aggregateSink: none
itemOutput: json_object
allowedTools: read_file, context_ref

### Item Loop
读取当前对象，返回校验 JSON。

### Finalize
根据 manifest done/failed 和 item refs 汇总校验结论。
```

## 常见错误

错误：Item Loop 要求模型调用 `manifest.done`。

正确：managed runner 下，manifest 状态由框架更新。

错误：Item Loop 要求模型 `write_file` 或 `append_file`。

正确：中间结果由 `resultSink` 和 `aggregateSink` 声明，框架负责写。

错误：让模型在每个 item 后判断是否生成最终 Excel。

正确：最终产物只在 Finalize 阶段生成。

错误：skill 示例里写了占位 item，模型直接拿去创建 manifest。

正确：skill 必须要求模型先读取真实输入列表。

错误：所有 skill 都强制写单项 JSON 文件。

正确：是否写单项文件由 `resultSink` 决定。综述类 skill 可以只用 `context_ref` 和 Markdown 聚合。

## 兼容旧 skill

旧 skill 如果只写了：

```text
itemResultPathTemplate: ...
aggregatePathTemplate: ...
itemOutput: json_object
```

JobClaw 会按下面方式理解：

```text
resultSink: both
aggregateSink: jsonl
```

新 skill 建议显式写出 `resultSink` 和 `aggregateSink`，这样复杂流程更容易维护。
