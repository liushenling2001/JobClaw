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

当前版本的重点是把执行链路从“模型回答”推进到“任务闭环”：

- 任务运行拥有显式 phase
- 工具调用和验证过程可追踪
- 常见失败会进入 repair
- 修复不是盲重试，而是基于失败类型做定向处理

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

当前 `TaskHarness` 已支持：

- `PLAN -> ACT -> OBSERVE -> VERIFY -> REPAIR -> FINISH/FAILED`
- 结构化 step 记录
- `runId` 级别追踪
- verifier failure type
- repair prompt 定向指引
- 按失败类型区分 repair budget

### 3. 结果校验与修复

当前 verifier 已覆盖：

- 空响应
- 错误响应
- 执行异常
- 测试命令失败
- 文件写入未落地
- 命令非零退出

当前 failure type 包括：

- `EXECUTION_FAILURE`
- `EMPTY_RESPONSE`
- `ERROR_RESPONSE`
- `TEST_COMMAND`
- `FILE_EXPECTATION`
- `COMMAND_EXIT`

### 4. 多智能体协作

支持：

- `spawn`
- `collaborate`

协作模式：

- `TEAM`
- `SEQUENTIAL`
- `DEBATE`

同时提供共享黑板能力：

- `SharedBoardService`
- `board` 相关执行事件可进入 trace

### 5. 工具体系

当前内建工具覆盖这些方向：

- 文件：`read_file` `write_file` `edit_file` `append_file` `list_dir`
- 命令：`run_command` `exec`
- 网络：`web_search` `web_fetch`
- 系统：`cron` `message` `query_token_usage`
- MCP：`mcp`
- 多智能体：`spawn` `collaborate`
- Agent 管理：`agent_catalog`
- 协作：`board_write` `board_read`

### 6. Web Console

后端已提供完整 Web API 与 SSE 流：

- 聊天
- 执行流
- 会话管理
- 配置管理
- Cron 管理
- MCP 管理
- 技能管理
- TaskHarness run 查询

说明：

- 当前前端已经能提交任务并走完整后端 TaskHarness
- 前端还没有把 TaskHarness 做成专门 UI 面板
- 但后端的 run / events 查询接口已经可用

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

### 当前行为

每次任务执行时：

1. 创建 `runId`
2. 启动 TaskHarness run
3. 进入主执行
4. 对结果执行 verifier
5. 如果失败，生成结构化 failure
6. 基于 failure type 生成 repair prompt
7. 在预算内循环 repair
8. 成功结束或失败结束

### 当前可观测接口

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
      -> AgentLoop
        -> ContextAssembler + ContextAssemblyPolicy
        -> LLM + ToolCallbacks
        -> SessionSummarizer

Storage / Retrieval
  -> ConversationStore
  -> SummaryService
  -> RetrievalService (SQLite FTS5)
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
- `POST /api/chat/stream`
- `POST /api/execute/stream`
- `GET /api/execute/stream/{sessionKey}`
- `GET /api/sessions`
- `GET /api/sessions/{key}`
- `GET /api/sessions/search`
- `DELETE /api/sessions/{key}`
- `GET /api/task-harness/runs/{runId}`
- `GET /api/task-harness/runs/{runId}/events`

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
- `search.db`
- `agents.db`
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

## 当前里程碑

当前这版可以看作 JobClaw 从“对话代理”升级到“任务执行运行时”的第一阶段里程碑：

- 执行链路显式化
- failure type 结构化
- repair loop 可控
- TaskHarness 可查询
- Web SSE 与 run 查询打通

如果你要继续推进，下一阶段最自然的是：

- 前端可视化 TaskHarness
- 更细粒度的 expectation extraction
- 更强的 repair policy

## License

MIT
