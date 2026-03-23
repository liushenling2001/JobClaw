# JobClaw 👨‍🔧

**AI Agent Framework based on Spring Boot 3.3 + Java 17 + Spring AI**

JobClaw 是一个轻量级、可扩展的 AI Agent 框架，基于 Spring Boot 3.3、Java 17 和 Spring AI 1.0 构建。支持多智能体协作、多通道消息接入、工具系统、技能扩展等功能，适用于构建企业级 AI 助手和自动化应用。

---

## ✨ 核心特性

### 🤖 AI 能力
- **Spring AI 1.0** - 基于 Spring AI 1.0.0-SNAPSHOT，支持 OpenAI 兼容模式
- **多 LLM Provider** - 支持 DashScope、OpenAI、Anthropic、智谱 AI、Gemini、Ollama 等
- **多智能体协作** - 7 种预置角色（程序员、研究员、作家、审查员、规划师、测试员、助手）
- **工具调用** - 基于 Spring AI @Tool 注解，支持文件读写、目录浏览等内置工具

### 💬 通道集成
- **多通道支持** - Telegram、Discord、钉钉、飞书、QQ、WhatsApp、MaixCam
- **官方 SDK** - 飞书通道使用 lark-oapi-sdk v2.5.3
- **WebSocket 长连接** - 无需公网 IP 即可接收消息
- **Webhook 模式** - 支持传统 HTTP 回调

### 🛠️ 扩展能力
- **技能系统** - 可扩展的技能注册机制
- **MCP 集成** - 支持 Model Context Protocol 服务器
- **定时任务** - 基于 Cron 的任务调度
- **心跳服务** - 健康检查和周期性任务

### 🌐 管理界面
- **Web Console** - 内置 Web 管理界面（http://localhost:18791）
- **REST API** - 提供 `/api/status`、`/api/chat`、`/api/chat/stream` 等接口
- **SSE 流式输出** - 支持实时消息流

### 🔐 安全特性
- **命令黑名单** - 防止危险命令执行
- **工作空间隔离** - 限制文件访问范围
- **安全守卫层** - 多层安全防护
- **通道权限校验** - 支持白名单控制

---

## 🚀 快速开始

### 环境要求

- **JDK**: 17+
- **Maven**: 3.6+
- **操作系统**: Linux / macOS / Windows

### 编译构建

```bash
# 克隆仓库
git clone https://github.com/liushenling2001/JobClaw.git
cd JobClaw

# 编译打包
mvn clean package -DskipTests

# 构建产物
ls -lh target/jobclaw-1.0.0.jar  # 约 61MB
```

### 初始化配置

```bash
# 第一次运行需要初始化
java -jar target/jobclaw-1.0.0.jar onboard
```

这会创建：
- 配置文件：`~/.jobclaw/config.json`
- 工作空间目录：`~/.jobclaw/workspace`

### 配置 API Key

编辑 `~/.jobclaw/config.json`：

```json
{
  "providers": {
    "dashscope": {
      "apiKey": "sk-your-api-key-here",
      "apiBase": "https://coding.dashscope.aliyuncs.com"
    }
  },
  "agent": {
    "model": "qwen3.5-plus",
    "provider": "dashscope"
  }
}
```

**推荐配置（DashScope Coding Plan）：**
- 使用 OpenAI 兼容接口
- 支持通义千问系列模型
- 性价比高，响应速度快

### 启动服务

**方式 1: 命令行模式（CLI）**
```bash
java -jar target/jobclaw-1.0.0.jar agent
```

**方式 2: 网关模式（多通道 + Web Console）**
```bash
java -jar target/jobclaw-1.0.0.jar gateway
```

**方式 3: 运行演示**
```bash
# 单 Agent 演示
java -jar target/jobclaw-1.0.0.jar demo agent-basic

# 多 Agent 协作演示
java -jar target/jobclaw-1.0.0.jar demo agent-multi
```

**访问 Web Console:**
```
http://localhost:18791
```

---

## 🤖 多智能体系统

### 预置角色

| 角色 | 代码 | 职责 | 适用场景 |
|------|------|------|----------|
| 👨‍💼 Assistant | `assistant` | 通用助手 | 日常问答、简单任务 |
| 👨‍💻 Coder | `coder` | 程序员 | 代码编写、审查、调试 |
| 🔬 Researcher | `researcher` | 研究员 | 信息收集、分析、总结 |
| ✍️ Writer | `writer` | 作家 | 文档创作、内容编辑 |
| 🔍 Reviewer | `reviewer` | 审查员 | 质量检查、风险评估 |
| 📋 Planner | `planner` | 规划师 | 任务分解、计划制定 |
| 🧪 Tester | `tester` | 测试员 | 测试验证、Bug 发现 |

### 使用方式

**1. 自动多 Agent 模式**

请求中包含以下关键词时自动启用：
- "多智能体"、"多 agent"、"multi-agent"
- "协作"、"协同"、"团队"、"team"

示例：
```
请用多智能体协作模式开发一个 Java 工具类...
用团队协作完成这个任务...
```

**2. 指定角色**

使用"作为"、"扮演"、"用"等关键词指定角色：

```
作为程序员，请帮我编写一个 JSON 处理工具...
扮演研究员，分析这个技术方案的优缺点...
用 coder 角色开发一个 REST API...
```

**3. CLI 演示**

```bash
# 单 Agent 演示
java -jar target/jobclaw-1.0.0.jar demo agent-basic

# 多 Agent 协作演示
java -jar target/jobclaw-1.0.0.jar demo agent-multi
```

### 协作流程

```
用户请求
  ↓
Orchestrator 分析（自动识别多 Agent 需求）
  ↓
1️⃣ Planner 分析任务并分解步骤
  ↓
2️⃣ Executors 执行（Coder/Researcher/Writer/Tester）
  ↓
3️⃣ Reviewer 质量审查
  ↓
4️⃣ Writer 汇总输出
  ↓
用户收到完整结果
```

---

## 🛠️ 工具系统

### 完整工具列表 (13 个)

#### 文件操作工具 (5 个)

| 工具 | 功能 | 参数 |
|------|------|------|
| `read_file` | 读取任意文本文件 | `path` (必需) |
| `write_file` | 写入/创建文件 | `path`, `content` (必需) |
| `list_dir` | 浏览目录内容 | `path` (必需) |
| `edit_file` | 精确文本替换 | `path`, `old_text`, `new_text` |
| `append_file` | 追加内容到文件末尾 | `path`, `content` |

#### 文档处理工具 (2 个)

| 工具 | 功能 | 参数 |
|------|------|------|
| `read_word` | 读取 Word 文档 (.doc/.docx) | `path` (必需) |
| `read_excel` | 读取 Excel 表格 (.xls/.xlsx) | `path`, `sheet_name`, `max_rows` |

#### 命令执行工具 (1 个)

| 工具 | 功能 | 参数 |
|------|------|------|
| `exec` | 执行 Shell 命令 | `command`, `timeout_seconds`, `workdir` |

#### 网络工具 (2 个)

| 工具 | 功能 | 参数 |
|------|------|------|
| `web_search` | 网页搜索 (Brave API) | `query`, `count` |
| `web_fetch` | 抓取网页内容 | `url`, `extract_mode` |

#### 系统工具 (3 个)

| 工具 | 功能 | 参数 |
|------|------|------|
| `cron` | 定时任务管理 | `action`, `message`, `at_seconds`, `every_seconds`, `cron_expr` |
| `message` | 发送消息到 IM 通道 | `channel`, `content`, `chat_id` |
| `query_token_usage` | 查询 Token 使用统计 | `type`, `start_date`, `end_date` |

#### 多 Agent 工具 (1 个)

| 工具 | 功能 | 参数 |
|------|------|------|
| `spawn` | 生成子 Agent | `task`, `label`, `async`, `role` |

### 工具调用示例

#### 文件操作

**读取文件：**
```
用户：读取 /home/user/config.json 的内容
Agent → read_file(path="/home/user/config.json")
```

**编辑文件：**
```
用户：把 config.json 里的 debug 改为 true
Agent → edit_file(
  path="/home/user/config.json",
  old_text="debug: false",
  new_text="debug: true"
)
```

#### 文档处理

**读取 Word 文档：**
```
用户：读取会议记录.docx
Agent → read_word(path="/docs/会议记录.docx")
```

**读取 Excel 表格：**
```
用户：查看销售数据.xlsx 的前 10 行
Agent → read_excel(
  path="/docs/销售数据.xlsx",
  max_rows=10
)
```

#### 命令执行

**执行 Shell 命令：**
```
用户：列出当前目录下的所有文件
Agent → exec(command="ls -la", timeout_seconds=30)
```

#### 网络工具

**网页搜索：**
```
用户：搜索 Spring AI 的最新版本
Agent → web_search(query="Spring AI latest version 2026", count=5)
```

**抓取网页：**
```
用户：获取 https://spring.io 的内容
Agent → web_fetch(url="https://spring.io", extract_mode="markdown")
```

#### 定时任务

**一次性提醒：**
```
用户：10 分钟后提醒我喝水
Agent → cron(
  action="add",
  message="喝水休息",
  at_seconds=600
)
```

**周期性任务：**
```
用户：每 2 小时检查一次邮箱
Agent → cron(
  action="add",
  message="检查邮箱",
  every_seconds=7200
)
```

**Cron 表达式：**
```
用户：每天早上 9 点提醒晨会
Agent → cron(
  action="add",
  message="晨会提醒",
  cron_expr="0 9 * * *"
)
```

#### 消息发送

**发送通知：**
```
用户：通知所有用户服务器维护
Agent → message(
  channel="feishu",
  content="⚠️ 服务器将于今晚 23:00 进行维护，预计持续 2 小时",
  chat_id="all_users"
)
```

#### Token 统计

**查询今日用量：**
```
用户：今天用了多少 Token？
Agent → query_token_usage(type="today")
```

**查询总用量：**
```
用户：总共花了多少 API 费用？
Agent → query_token_usage(type="total")
```

**查询指定范围：**
```
用户：查询 3 月份的 Token 使用
Agent → query_token_usage(
  type="range",
  start_date="2026-03-01",
  end_date="2026-03-31"
)
```

---

## 💬 通道配置

### 飞书通道

**飞书开放平台配置：**
1. 访问 https://open.feishu.cn/app 创建企业自建应用
2. 在"凭证与基础信息"中获取 App ID 和 App Secret
3. 在"事件订阅"中选择"使用长连接接收事件"
4. 订阅 `im.message.receive_v1` 事件
5. 在"机器人"能力中启用机器人

**config.json 配置：**
```json
{
  "channels": {
    "feishu": {
      "enabled": true,
      "appId": "cli_xxxxxxxxxxxxx",
      "appSecret": "xxxxxxxxxxxxx",
      "connectionMode": "websocket",
      "allowFrom": ["open_id_xxx"]
    }
  }
}
```

### 支持的通道

| 通道 | 状态 | 备注 |
|------|------|------|
| 飞书 (Feishu) | ✅ 官方 SDK | WebSocket 长连接 |
| 钉钉 (DingTalk) | ✅ | Webhook |
| QQ | ✅ | 协议接入 |
| Telegram | ✅ | Bot API |
| Discord | ✅ | JDA |
| WhatsApp | ✅ | Business API |
| MaixCam | ✅ | 本地摄像头 |

---

## 📖 CLI 命令

| 命令 | 描述 | 示例 |
|------|------|------|
| `onboard` | 初始化配置和工作空间 | `jobclaw onboard` |
| `agent` | 与 Agent 交互（CLI 模式） | `jobclaw agent` |
| `gateway` | 启动网关服务 | `jobclaw gateway` |
| `status` | 显示系统状态 | `jobclaw status` |
| `demo` | 运行演示 | `jobclaw demo agent-basic` |
| `version` | 显示版本信息 | `jobclaw version` |
| `cron` | 管理定时任务 | `jobclaw cron list` |
| `skills` | 管理技能 | `jobclaw skills list` |
| `mcp` | 管理 MCP 服务器 | `jobclaw mcp list` |

**演示模式：**
- `agent-basic` - 单 Agent 基础演示
- `agent-multi` - 多 Agent 协作演示

---

## 🔧 配置说明

### LLM Provider 配置

```json
{
  "providers": {
    "dashscope": {
      "apiKey": "sk-xxx",
      "apiBase": "https://coding.dashscope.aliyuncs.com"
    },
    "openai": {
      "apiKey": "sk-xxx",
      "apiBase": "https://api.openai.com"
    },
    "anthropic": {
      "apiKey": "sk-xxx"
    },
    "zhipu": {
      "apiKey": "xxx"
    },
    "gemini": {
      "apiKey": "xxx"
    },
    "ollama": {
      "apiBase": "http://localhost:11434"
    }
  }
}
```

### Agent 配置

```json
{
  "agent": {
    "model": "qwen3.5-plus",
    "provider": "dashscope",
    "maxTokens": 2000
  }
}
```

### 端口配置

| 服务 | 端口 | 说明 |
|------|------|------|
| Gateway | 18791 | Web Console 和 API |
| MaixCam | 18790 | 摄像头服务 |

---

## 📁 项目结构

```
JobClaw/
├── src/main/java/io/jobclaw/
│   ├── agent/              # Agent 核心
│   │   ├── AgentLoop.java          # Agent 循环（Spring AI）
│   │   ├── AgentRole.java          # Agent 角色定义
│   │   ├── AgentRegistry.java      # Agent 注册表
│   │   └── AgentOrchestrator.java  # 多 Agent 编排器
│   ├── bus/                # 消息总线
│   │   └── MessageBus.java         # 消息分发
│   ├── channels/           # 通道实现
│   │   ├── FeishuChannel.java      # 飞书（官方 SDK）
│   │   ├── DingTalkChannel.java    # 钉钉
│   │   ├── QQChannel.java          # QQ
│   │   ├── TelegramChannel.java    # Telegram
│   │   └── ...
│   ├── cli/                # CLI 命令
│   │   ├── AgentCommand.java       # agent 命令
│   │   ├── GatewayCommand.java     # gateway 命令
│   │   └── DemoCommand.java        # demo 命令
│   ├── config/             # 配置管理
│   │   ├── Config.java             # 主配置
│   │   ├── JobClawConfig.java      # JobClaw 配置
│   │   └── AgentBeansConfig.java   # Spring Bean 配置
│   ├── cron/               # 定时任务
│   ├── heartbeat/          # 心跳服务
│   ├── mcp/                # MCP 集成
│   ├── providers/          # LLM Provider
│   │   └── DashscopeProvider.java  # 通义千问
│   ├── security/           # 安全守卫
│   ├── session/            # 会话管理
│   │   └── SessionManager.java     # 会话管理
│   ├── skills/             # 技能系统
│   ├── tools/              # 工具系统
│   │   ├── FileTools.java          # 文件工具
│   │   └── JobClawToolkit.java     # 工具集合
│   └── web/                # Web Console
│       └── WebConsoleController.java  # REST API
├── src/main/resources/
│   ├── application.yml     # Spring Boot 配置
│   └── static/             # 静态资源（HTML/CSS/JS）
├── pom.xml                 # Maven 依赖
├── MIGRATION_PLAN.md       # Spring AI 迁移文档
└── README.md               # 项目说明
```

---

## 🎯 使用示例

### 与 Agent 对话

```bash
# 启动 agent
java -jar target/jobclaw-1.0.0.jar agent

# 发送消息
> 你好，请介绍一下 JobClaw
```

## 🛠️ 可用工具

JobClaw 集成了 **14 个核心工具**，分为 5 大类：

### 文件操作工具（5 个）

| 工具名 | 描述 | 状态 |
|--------|------|------|
| `read_file` | 读取任意文本文件内容 | ✅ |
| `write_file` | 创建或覆盖写入文件 | ✅ |
| `list_dir` | 列出目录内容 | ✅ |
| `edit_file` | 精确替换文件中的文本（old_text 必须完全匹配） | ✅ |
| `append_file` | 在文件末尾追加内容 | ✅ |

### 文档处理工具（2 个）

| 工具名 | 描述 | 支持格式 | 状态 |
|--------|------|----------|------|
| `read_word` | 读取 Word 文档内容 | .doc, .docx | ✅ |
| `read_excel` | 读取 Excel 工作簿内容 | .xls, .xlsx | ✅ |

### 命令执行工具（1 个）

| 工具名 | 描述 | 状态 |
|--------|------|------|
| `exec` | 执行 Shell 命令并返回输出<br>- 跨平台支持（Windows/Linux/macOS）<br>- 超时控制（默认 60 秒）<br>- 输出截断（最大 10000 字符）<br>- ⚠️ 安全警告：需配合权限控制使用 | ✅ |

### 网络工具（2 个）

| 工具名 | 描述 | 状态 |
|--------|------|------|
| `web_search` | 使用 Brave Search API 搜索网络<br>- 需要配置 `BRAVE_API_KEY` 环境变量<br>- 可配置结果数量（1-10） | ✅ |
| `web_fetch` | 抓取网页并提取可读内容<br>- HTML 转文本（移除 script/style）<br>- JSON 内容支持<br>- URL 验证（仅 http/https） | ✅ |

### 系统工具（4 个）

| 工具名 | 描述 | 状态 |
|--------|------|------|
| `cron` | 定时任务管理<br>- 支持一次性提醒（at_seconds）<br>- 支持周期性任务（every_seconds）<br>- 支持复杂调度（cron_expr）<br>- 操作：add, list, remove, enable, disable | ⏳ 占位符 |
| `message` | 发送消息到指定通道<br>- 支持所有消息平台（feishu, telegram, whatsapp 等）<br>- 通道和 chat_id 定位 | ⏳ 占位符 |
| `spawn` | 生成子 Agent 处理任务<br>- 同步模式：等待结果并返回<br>- 异步模式：后台运行，完成后通知 | ⏳ 占位符 |
| `query_token_usage` | 查询 Token 使用统计<br>- 按日期范围查询<br>- 按模型分组统计<br>- 按日期分组统计 | ⏳ 占位符 |

**图例**：
- ✅ 已实现并可用
- ⏳ 占位符（待服务集成后完全可用）

---

### 工具使用示例

#### 文件操作

```bash
# 读取文件
read_file(path="/home/user/config.json")

# 写入文件
write_file(path="/tmp/test.txt", content="Hello World")

# 精确编辑文件（old_text 必须完全匹配）
edit_file(path="config.json", 
          old_text='"port": 8080', 
          new_text='"port": 9090')

# 追加内容到文件末尾
append_file(path="log.txt", content="2024-01-01 12:00:00 - App started\n")

# 列出目录内容
list_dir(path="/home/user")
```

#### 文档处理

```bash
# 读取 Word 文档
read_word(path="/home/user/report.docx")

# 读取 Excel 工作簿
read_excel(path="/home/user/data.xlsx", sheet="Sheet1")
```

#### 命令执行

```bash
# 执行 Git 命令
exec(command="git status")

# 执行 Maven 构建（带超时）
exec(command="mvn clean package", workingDir="/home/user/JobClaw", timeout=120)
```

#### 网络搜索

```bash
# 搜索 AI 新闻
web_search(query="AI news 2024", count=5)

# 搜索 Spring AI 教程
web_search(query="Spring AI tutorial", count=10)
```

#### 网页抓取

```bash
# 抓取网页内容
web_fetch(url="https://example.com")

# 抓取 JSON API
web_fetch(url="https://api.example.com/data.json", maxChars=10000)
```

#### 定时任务

```bash
# 10 分钟后提醒
cron(action="add", message="喝水休息", at_seconds=600)

# 每小时提醒
cron(action="add", message="检查系统状态", every_seconds=3600)

# 每天上午 9 点提醒（Cron 表达式）
cron(action="add", message="晨会提醒", cron_expr="0 9 * * *")

# 列出所有任务
cron(action="list")

# 删除任务
cron(action="remove", job_id="job_1234567890")
```

---

### 使用工具

```
用户：读取 /home/user/test.txt 的内容
Agent: [使用 read_file 工具读取文件并返回内容]
```

### 多 Agent 协作

```
用户：请用多智能体协作模式开发一个 Java 工具类，用于读取和写入 JSON 文件

系统：
1️⃣ Planner 分析任务并分解步骤
2️⃣ Coder 编写代码 + Researcher 调研方案
3️⃣ Reviewer 审查代码质量
4️⃣ Writer 汇总输出完整结果
```

### Web API 调用

```bash
# 查看状态
curl http://localhost:18791/api/status

# 发送消息
curl -X POST http://localhost:18791/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "你好", "sessionKey": "web:test"}'

# 流式输出
curl http://localhost:18791/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "你好"}'
```

---

## 🔄 版本历史

### v1.0.0 - Spring AI 迁移 + 多智能体支持 (2026-03-23)

**框架迁移:**
- ✅ 从 AgentScope 迁移到 Spring AI 1.0.0-SNAPSHOT
- ✅ 使用 OpenAI 兼容模式支持 DashScope Coding Plan
- ✅ 重构 AgentLoop 使用 ChatClient + OpenAiChatModel
- ✅ 工具系统使用 Spring AI @Tool 注解

**多智能体系统:**
- ✅ 新增 AgentOrchestrator（编排器）
- ✅ 新增 AgentRegistry（Agent 注册表）
- ✅ 新增 AgentRole（7 种预置角色）
- ✅ 支持自动多 Agent 协作
- ✅ 支持角色指定（"作为程序员..."）
- ✅ 4 步协作流程（规划→执行→审查→汇总）

**通道升级:**
- ✅ 飞书通道使用官方 SDK（lark-oapi-sdk v2.5.3）
- ✅ 移除自定义 OkHttp 实现
- ✅ 简化 WebSocket 连接和事件处理

**性能优化:**
- ✅ 修复工具调用循环问题
- ✅ 优化系统提示词
- ✅ 添加 maxTokens 限制

**详细说明:** [MIGRATION_PLAN.md](MIGRATION_PLAN.md)

---

## 🔐 安全

- **命令黑名单** - 防止危险命令执行
- **工作空间隔离** - 限制文件访问范围
- **安全守卫层** - 多层安全防护
- **通道权限校验** - 支持白名单控制

---

## 📄 License

MIT License

---

## 🙏 致谢

- **Spring Boot 团队** - Spring Boot 3.3 框架
- **Spring AI 团队** - Spring AI 1.0 框架
- **飞书开放平台** - 飞书官方 SDK (lark-oapi-sdk)
- **各 LLM Provider** - DashScope、OpenAI、Anthropic、智谱 AI、Gemini、Ollama

---

## 📬 联系方式

- **GitHub Issues**: [提交问题](https://github.com/liushenling2001/JobClaw/issues)
- **GitHub 仓库**: https://github.com/liushenling2001/JobClaw
- **Email**: 22607104@qq.com

---

**JobClaw** - 构建智能、协作、可扩展的 AI 应用 👨‍🔧🤖
