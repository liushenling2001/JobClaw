# JobClaw

JobClaw 是一个以 Spring Boot + Spring AI 为核心的多智能体执行框架，提供 CLI、Web Console、工具调用、会话检索、持久化 Agent Catalog、以及共享黑板协作能力。

当前版本重点不是“页面操作编排”，而是后端执行架构升级：
- 会话改为 append-only 原始消息存储。
- 上下文改为 message list 分层装配（recent history / summary / memory / retrieval）。
- 检索层独立为 SQLite FTS5（history / summary / memory 分离检索）。
- 多智能体支持持久化自定义 agent + 内置角色 + async 可观测执行。
- TEAM / SEQUENTIAL / DEBATE 协作已接入 Shared Board。

## 核心能力

### 1) 对话与上下文架构
- 原始消息持久化：`ConversationStore`（`messages.ndjson`，append-only）。
- 摘要层：`chunk summaries`、`session summary`、`memory facts`。
- 检索层：`RetrievalService`（默认 `SqliteRetrievalService`）。
- 装配层：`ContextAssembler` + `ContextAssemblyPolicy`。
- 兼容层：`SessionManager` 保留 Web API 所需会话视图。

### 2) 多智能体运行
- `spawn`：创建子智能体执行任务（支持同步/异步）。
- `collaborate`：支持三种模式。
1. `TEAM`：并行协作。
2. `SEQUENTIAL`：串行接力。
3. `DEBATE`：正反辩论。
- 子智能体来源：
1. 内置角色（assistant/coder/researcher/writer/reviewer/planner/tester）。
2. 持久化自定义 agent（由 `agent_catalog` 创建和管理）。

### 3) 共享黑板协作
- `SharedBoardService` 文件化落盘。
- `board_write` / `board_read` 工具可被协作流程调用。
- 协作过程中的 board 事件会进入 execution trace，前端可展示进展。

### 4) Web Console 与 API
- 聊天、流式输出、会话管理、配置管理、工具与技能管理。
- 会话搜索接口已提供：`/api/sessions/search`。

## 技术栈

- Java 17
- Spring Boot 3.4.0
- Spring AI 1.1.0
- SQLite (xerial sqlite-jdbc)
- Vue 3 + Vite（`ui/`）

## 快速开始

### 环境要求
- JDK 17+
- Maven 3.6+
- Node.js 18+（仅前端开发或重建静态资源需要）

### 构建
```bash
mvn clean package -DskipTests
```

### 初始化（首次）
```bash
java -jar target/jobclaw-1.0.0.jar onboard
```

默认会生成：
- 配置文件：`~/.jobclaw/config.json`
- 工作空间：`~/.jobclaw/workspace`

### 配置 Provider（示例）
编辑 `~/.jobclaw/config.json`：

```json
{
  "agent": {
    "provider": "dashscope",
    "model": "qwen3.5-plus"
  },
  "providers": {
    "dashscope": {
      "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
      "apiKey": "sk-xxxx"
    },
    "openai": {
      "baseUrl": "https://api.openai.com/v1",
      "apiKey": ""
    },
    "ollama": {
      "baseUrl": "http://localhost:11434/v1",
      "apiKey": ""
    }
  }
}
```

### 启动
```bash
java -jar target/jobclaw-1.0.0.jar gateway
```

Web Console 默认地址：
- `http://localhost:18791`

CLI 模式：
```bash
java -jar target/jobclaw-1.0.0.jar agent
```

## 常用命令

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

## 关键工具（Agent Tooling）

- 文件与命令：`read_file` `write_file` `list_dir` `edit_file` `append_file` `run_command` `exec`
- 网络：`web_search` `web_fetch`
- 系统：`cron` `message` `query_token_usage` `mcp`
- 多智能体：`spawn` `collaborate`
- Agent 管理：`agent_catalog`
- 协作黑板：`board_write` `board_read`

## API 概览（节选）

- `POST /api/chat`
- `POST /api/chat/stream`
- `POST /api/execute/stream`
- `GET /api/execute/stream/{sessionKey}`
- `GET /api/sessions`
- `GET /api/sessions/{key}`
- `GET /api/sessions/search`
- `DELETE /api/sessions/{key}`
- `GET /api/channels` / `PUT /api/channels/{name}`
- `GET /api/providers` / `PUT /api/providers/{name}`

## 数据与存储布局

默认目录：`~/.jobclaw/workspace/sessions/conversation`

主要内容：
- 每会话目录下：
1. `messages.ndjson`（原始消息，append-only）
2. `session.json`（会话元数据投影）
3. `chunks.ndjson`
4. `chunk-summaries.ndjson`
5. `session-summary.json`
6. `memory-facts.ndjson`
- 检索索引：
1. `search.db`（SQLite FTS5）
- Agent Catalog：
1. `agents.db`
- Shared Board：
1. `boards/`

## 架构分层（当前实现）

```text
Web/CLI
  -> AgentOrchestrator
    -> AgentLoop
      -> ContextAssembler + ContextAssemblyPolicy
      -> LLM + ToolCallbacks
      -> SessionSummarizer

Storage & Retrieval
  -> ConversationStore (append-only messages)
  -> SummaryService (chunk/session/facts)
  -> RetrievalService (SQLite FTS5)
  -> AgentCatalogStore (SQLite)
  -> SharedBoardService (file-based)
```

## 前端开发

前端代码在 `ui/`：

```bash
cd ui
npm install
npm run dev
```

构建并同步到后端静态资源：
```bash
cd ui
npm run build
```

## 测试与验证

后端编译：
```bash
mvn -DskipTests compile
```

后端测试：
```bash
mvn test
```

前端构建验证：
```bash
cd ui
npm run build
```

## 相关设计文档

- `docs/conversation-architecture-refactor.md`
- `docs/natural-language-agent-runtime-design.md`
- `docs/multi-agent-shared-board-design.md`

## License

MIT
