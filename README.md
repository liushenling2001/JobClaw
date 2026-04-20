# JobClaw

JobClaw 是一个基于 Spring Boot + Spring AI 的智能体执行系统。  
它不是一个“只会聊天”的壳，而是一个面向真实任务执行的后端运行时，提供：

- CLI 与 Web Console 双入口
- 可调用工具体系
- 会话持久化、摘要、记忆、检索
- 多智能体协作
- MCP 接入
- Cron 调度
- 可观测的执行事件流
- 面向结果校验与失败修复的 `TaskHarness`
- 显式任务完成控制、checkpoint 与经验学习

当前版本的重点是把执行链路从“模型回答”推进到“任务闭环”：

- 任务运行拥有显式 phase
- 任务开始时生成 `DoneDefinition`
- 工具调用和验证过程可追踪
- 未完成任务会区分 `CONTINUE`、`REPAIR`、`BLOCKED`
- 修复不是盲重试，而是基于失败类型做定向处理
- 长任务会沉淀 checkpoint，经验会先进入候选区而不是直接改写系统行为

## 适用场景

JobClaw 适合这些类型的系统：

- 需要让 LLM 调用文件、命令、网络等工具
- 需要把运行过程开放给 Web 前端观察
- 需要会话记忆、摘要、检索，而不是单轮 prompt
- 需要多智能体协作，而不是单 Agent 串行输出
- 需要对“是否真的完成任务”做结果校验

## 当前能力

### 1. 会话与上下文

- 原始消息持久化
- Chunk summary、session summary、memory facts
- 基于 SQLite FTS5 的检索层
- 分层上下文组装
- append-only 会话存储

核心组件：

- `ConversationStore`
- `SummaryService`
- `RetrievalService`
- `ContextAssembler`
- `ContextAssemblyPolicy`

### 2. 执行运行时

- `AgentLoop` 负责单轮模型与工具交互
- `AgentOrchestrator` 负责主执行入口
- `ExecutionTraceService` 负责执行事件流与 SSE
- `TaskHarness` 负责任务级执行闭环
- `TaskPlanningPolicy` 负责任务开始时的轻量规划分类

当前 `TaskHarness` 已支持：

- `PLAN -> ACT -> OBSERVE -> VERIFY -> REPAIR -> FINISH/FAILED`
- 结构化 step 记录
- `runId` 级别追踪
- verifier failure type
- repair prompt 定向指引
- 按失败类型区分 repair budget
- `subtasks` worklist 约束
- `WORKLIST` 任务未建 subtasks 时禁止通过 verify
- `TaskCompletionController` 统一判断 `COMPLETE/CONTINUE/REPAIR/BLOCKED`
- `TaskCheckpointService` 自动记录长任务中间状态

完成判断不再只依赖最终文本。当前运行时会综合：

- 规划类型与 `DoneDefinition`
- worklist / subtasks 状态
- 活跃工具与活跃子智能体
- 文件或报告等 artifact 是否真实落地
- 是否只是 planning-only 回复
- provider / tool failure 类型

### 3. 结果校验与修复

当前 completion runtime 已覆盖：

- 空响应
- 错误响应
- 执行异常
- 测试命令失败
- 文件写入未落地
- 命令非零退出
- 批量任务未完成 worklist
- 报告或文件已经生成后重复执行
- 子任务 pending 时误判结束

当前 failure type 包括：

- `EXECUTION_FAILURE`
- `EMPTY_RESPONSE`
- `ERROR_RESPONSE`
- `TEST_COMMAND`
- `FILE_EXPECTATION`
- `COMMAND_EXIT`

说明：

- 旧 verifier 仍保留，但降级为 evidence 层
- 最终是否结束由 `TaskCompletionController` 决定
- `CONTINUE` 表示继续推进，不会立即进入 repair
- `REPAIR` 表示缺少必要结构或产物，需要定向修复

### 4. 多智能体协作

支持：

- `spawn`
- `collaborate`
- `subtasks`

协作模式：

- `TEAM`
- `SEQUENTIAL`
- `DEBATE`

同时提供共享黑板能力：

- `SharedBoardService`
- `board` 相关执行事件可进入 trace

当前子任务语义：

- `spawn()`：默认继承当前主智能体配置，只隔离子任务上下文
- `spawn(role='...')`：加载对应角色智能体配置，并在父配置上做覆盖
- `spawn(agent='...')`：加载用户自定义智能体配置，并在父配置上做覆盖
- `subtasks(action='plan', ...)`：登记独立 worklist，父任务在 pending subtasks 未清空前不允许结束

### 5. 工具体系

当前内建工具覆盖这些方向：

- 文件：`read_file` `write_file` `edit_file` `append_file` `list_dir`
- 命令：`run_command` `exec`
- 网络：`web_search` `web_fetch`
- 系统：`cron` `message` `query_token_usage`
- 记忆：`memory`
- MCP：`mcp`
- 多智能体：`spawn` `collaborate`
- Agent 管理：`agent_catalog`
- 协作：`board_write` `board_read`

文件读取说明：

- PDF / Word / Excel 读取统一切换到 Apache Tika 优先路径
- `read_pdf`、`read_word` 支持读取前 N 页、中间随机 N 页、最后 N 页
- `list_dir` 会输出可直接复用的绝对路径
- `read_file` 会尽量恢复模型误插入空格导致的文件名错误

工具与技能注入说明：

- skills 不再默认全量塞进 prompt，而是按任务语义检索式注入
- tools 会按任务类型动态选择核心工具集
- 这样可以降低上下文膨胀，同时避免模型被无关工具干扰

### 6. 记忆、经验与学习候选

JobClaw 当前把“记忆”和“技能”分成不同层级：

- Memory：用户事实、偏好、显式要求记住的内容
- Workflow Memory：多次成功或用户总结出的可复用流程
- Learning Candidates：待确认的经验候选
- Skills：成熟、稳定、可长期复用的能力说明

当前策略：

- 用户调用 `memory(action="remember")` 时会写入长期记忆
- 如果内容像“总结经验 / 工作流 / 下次按这个做”，会额外生成 `WORKFLOW` 学习候选
- 如果内容像“失败教训 / 避免 / 不要再”，会额外生成 `NEGATIVE_LESSON` 学习候选
- 候选默认是 `PENDING`，需要用户确认后才进入正式经验系统
- 系统不会因为一次模型回复就自动改写核心行为

内置经验复盘：

- `experience-review` 是内置 skill
- `onboard` 初始化时会写入默认 cron 配置
- 默认每日 01:00 复盘成功/失败任务并沉淀候选经验
- 复盘只在关键节点使用 LLM，不会在每一步都调用模型

相关数据默认存放在 workspace 下：

- `memory/MEMORIES.json`
- `.jobclaw/learning/candidates.json`
- `.jobclaw/experience/memories.json`
- `.jobclaw/workflows/recipes.json`

### 7. Web Console

后端已提供完整 Web API 与 SSE 流：

- 聊天
- 执行流
- 会话管理
- 智能体管理
- 配置管理
- Cron 管理
- MCP 管理
- 技能管理
- TaskHarness run 查询
- 学习候选与经验管理

说明：

- 当前前端聊天主流程使用 `POST /api/execute/stream`
- 当前前端聊天与 Feishu 通道都已接入 `AgentOrchestrator + TaskHarness`
- `/dashboard` 等前端路由刷新会回退到静态入口
- 静态资源会在 Maven 打包时同步进 jar，避免 CSS/JS 哈希文件过期

### 8. 智能体配置模型

当前系统区分三类智能体：

- 主智能体
  - 配置来源：`~/.jobclaw/config.json`
  - 在 Web 页面中只读展示
- 系统角色智能体
  - 配置来源：`<workspace>/.jobclaw/agents/<role>.json`
  - 可在 Web 页面编辑、启停、克隆
- 用户自定义智能体
  - 配置来源：`<workspace>/.jobclaw/agents/<code>.json`
  - 可在 Web 页面编辑、启停、克隆、删除

说明：

- `default inherit` 不再作为独立配置实体出现在智能体列表中
- 它只是 `spawn()` 未指定 `role/agent` 时的运行时策略
- 系统角色和用户智能体共用 workspace 下的 JSON 文件存储
- 子智能体默认继承主智能体 provider / model / tools / skills
- 指定 `role` 或 `agent` 时，只覆盖该智能体明确配置的字段
- apiKey 永远来自主配置 `providers`，不会写进子智能体文件

## TaskHarness 说明

这是当前版本最重要的运行时能力。

### 执行模型

TaskHarness 把一次任务运行定义为一个有状态的执行过程，而不是一次纯文本响应。

核心 phase：

- `PLAN`
- `ACT`
- `OBSERVE`
- `VERIFY`
- `REPAIR`
- `FINISH`
- `FAILED`

### 规划模式

`TaskHarness` 在任务开始时会先通过 `TaskPlanningPolicy` 做一次轻量规划分类：

- `DIRECT`
  - 简单任务，直接执行
- `PHASED`
  - 多步骤但强耦合任务，例如“根据数据分析并撰写报告”
- `WORKLIST`
  - 多个独立子项任务，例如“批量审查目录下所有 PDF”

对于 `WORKLIST`：

- 运行时会自动追加 “先建立 subtasks worklist” 的指导约束
- verifier 会检查是否真的建立了 subtasks
- 未建立 worklist 的批任务不能直接结束

### 当前行为

每次任务执行时：

1. 创建 `runId`
2. 通过 `TaskPlanningPolicy` 生成 `TaskPlan`
3. 写入 `planningMode` 与 `DoneDefinition`
4. 进入主执行
5. 汇总工具、子任务、artifact、final response 等运行证据
6. 由 `TaskCompletionController` 输出 `COMPLETE/CONTINUE/REPAIR/BLOCKED`
7. `CONTINUE` 继续推进，`REPAIR` 才进入修复
8. 在预算内循环，直到成功结束或失败结束

### 当前可观测接口

- `POST /api/chat`
- `POST /api/execute/stream`
- SSE 事件：`task-harness-run`
- `GET /api/task-harness/runs/{runId}`
- `GET /api/task-harness/runs/{runId}/events`

你可以把这套接口看成“任务级调试入口”。

## 系统架构

```text
Web / CLI
  -> AgentOrchestrator
    -> TaskHarness
      -> TaskPlanningPolicy
      -> AgentLoop
        -> ContextAssembler + ContextAssemblyPolicy
        -> LLM + ToolCallbacks
        -> SessionSummarizer

Storage / Retrieval
  -> ConversationStore
  -> SummaryService
  -> RetrievalService (SQLite FTS5)
  -> MemoryStore
  -> LearningCandidateStore
  -> ExperienceMemoryStore
  -> WorkflowRecipeStore
  -> AgentCatalogStore
  -> SharedBoardService
  -> CronStore
```

## 技术栈

- Java 17
- Spring Boot 3.4.0
- Spring AI 1.1.0
- SQLite
- OkHttp
- Apache POI
- Apache Tika
- Vue 3 + Vite（静态资源已打包进 `src/main/resources/static`）

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.6+
- Node.js 18+  
  仅在你需要重新构建前端时才需要

### 构建

```bash
mvn clean package -DskipTests
```

### 首次初始化

```bash
java -jar target/jobclaw-1.0.0.jar onboard
```

默认会生成：

- 配置文件：`~/.jobclaw/config.json`
- 工作目录：`~/.jobclaw/workspace`

### 启动 Web Gateway

```bash
java -jar target/jobclaw-1.0.0.jar gateway
```

默认地址：

- [http://localhost:18791](http://localhost:18791)

### 启动 CLI Agent

```bash
java -jar target/jobclaw-1.0.0.jar agent
```

## CLI 命令

当前主要命令：

```bash
java -jar target/jobclaw-1.0.0.jar onboard
java -jar target/jobclaw-1.0.0.jar gateway
java -jar target/jobclaw-1.0.0.jar agent
java -jar target/jobclaw-1.0.0.jar status
java -jar target/jobclaw-1.0.0.jar skills
java -jar target/jobclaw-1.0.0.jar mcp
java -jar target/jobclaw-1.0.0.jar demo
java -jar target/jobclaw-1.0.0.jar version
```

## 配置

主配置文件：

- `~/.jobclaw/config.json`

最小示例：

```json
{
  "agent": {
    "provider": "dashscope",
    "model": "qwen3.5-plus",
    "maxTokens": 16384,
    "temperature": 0.7,
    "maxToolIterations": 20,
    "maxRepairAttempts": 1,
    "maxVerificationRepairAttempts": 1,
    "maxFileExpectationRepairAttempts": 2,
    "maxTestCommandRepairAttempts": 1,
    "maxCommandExitRepairAttempts": 1
  },
  "providers": {
    "dashscope": {
      "apiBase": "https://dashscope.aliyuncs.com/compatible-mode/v1",
      "apiKey": "sk-xxxx"
    },
    "openai": {
      "apiBase": "https://api.openai.com/v1",
      "apiKey": ""
    },
    "ollama": {
      "apiBase": "http://localhost:11434/v1",
      "apiKey": ""
    }
  }
}
```

### MCP 配置

当前系统支持在配置中声明 MCP 服务，并在运行时通过 `mcp` 工具使用。  
系统 prompt 中只会注入 MCP 的最简说明，不会展开冗长描述。

### Cron 存储

当前 Cron 存储采用最新版本格式。  
旧格式不会迁移，会在加载时清空并重建。

## Web API 概览

常用接口：

- `POST /api/chat`
- `POST /api/execute/stream`
- `GET /api/execute/stream/{sessionKey}`
- `GET /api/sessions`
- `GET /api/sessions/{key}`
- `GET /api/sessions/search`
- `DELETE /api/sessions/{key}`
- `GET /api/task-harness/runs/{runId}`
- `GET /api/task-harness/runs/{runId}/events`
- `GET /api/agents`
- `GET /api/agents/{id}`
- `POST /api/agents`
- `PUT /api/agents/{id}`
- `POST /api/agents/{id}/clone`
- `POST /api/agents/{id}/activate`
- `POST /api/agents/{id}/disable`
- `DELETE /api/agents/{id}`
- `GET /api/learning/candidates`
- `POST /api/learning/candidates/{id}/accept`
- `POST /api/learning/candidates/{id}/reject`
- `GET /api/experience/memories`

配置与管理接口还包括：

- channels
- providers
- models
- cron
- mcp
- skills
- token stats

## 数据目录

默认工作目录：

- `~/.jobclaw/workspace`

主要数据包括：

- 会话原始消息
- session 元数据
- chunk summaries
- session summary
- memory facts
- learning candidates
- accepted experience memories
- workflow recipes
- `search.db`
- `.jobclaw/agents/*.json`
- `boards/`
- cron 存储

## 前端说明

当前仓库中已经包含打包后的静态前端资源：

- `src/main/resources/static`

如果你要重新开发或重建前端，需要使用前端源码目录重新构建后再同步产物。  
当前后端对前端的兼容情况如下：

- 现有前端可以正常提交任务
- 后端执行已经走 TaskHarness
- 新增的 `task-harness-run` 事件不会破坏旧前端
- 新接口已经可用于后续前端增强

## 测试与验证

编译：

```bash
mvn -DskipTests compile
```

运行测试：

```bash
mvn test
```

当前 TaskHarness 相关的定向测试覆盖：

- verifier
- repair prompt
- execution trace filter
- repair strategy
- test command expectation
- file expectation
- command exit expectation
- completion controller
- checkpoint store
- dynamic tool / skill selection
- memory to learning candidate bridge
- Tika document reading

## 当前里程碑

当前这版可以看作 JobClaw 从“对话代理”升级到“任务执行运行时”的第一阶段里程碑：

- 执行链路显式化
- failure type 结构化
- completion runtime 可控
- checkpoint/resume 基础能力
- 经验学习候选机制
- 按需工具与技能注入
- TaskHarness 可查询
- Web SSE 与 run 查询打通

如果你要继续推进，下一阶段最自然的是：

- 前端可视化 TaskHarness
- 更细粒度的 checkpoint resume UI
- 更强的经验候选审核与合并
- 更细的 toolset / skillset 召回策略

## License

MIT
