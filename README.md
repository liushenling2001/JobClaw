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

### 内置工具

| 工具 | 功能 | 参数 |
|------|------|------|
| `read_file` | 读取文件内容 | `path` (必需) |
| `write_file` | 写入文件内容 | `path`, `content` (必需) |
| `list_dir` | 浏览目录内容 | `path` (必需) |

### 工具调用示例

**用户请求：**
```
读取 /home/user/config.json 的内容并分析
```

**Agent 执行流程：**
1. 调用 `read_file` 工具读取文件
2. 分析文件内容
3. 返回分析结果

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
