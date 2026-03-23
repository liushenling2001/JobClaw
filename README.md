# JobClaw 👨‍🔧

**AI Agent Framework based on Spring Boot 3.3 + Java 17 + Spring AI**

JobClaw 是一个轻量级的 AI Agent 框架，基于 Spring Boot 3.3、Java 17 和 Spring AI 1.0 构建，支持多通道消息接入、工具系统、技能扩展等功能。

## ✨ 特性

- 🚀 **Spring Boot 3.3** - 现代化的微服务框架
- 🤖 **Spring AI 1.0** - 基于 Spring AI 1.0.0-SNAPSHOT，支持 OpenAI 兼容模式
- 🤝 **多智能体协作** - 支持多 Agent 协作完成复杂任务（7 种预置角色）
- 💬 **多通道支持** - Telegram, Discord, 钉钉，飞书（官方 SDK）, QQ, WhatsApp
- 🛠️ **工具系统** - 文件读写、目录浏览等内置工具（基于 Spring AI @Tool）
- 🎯 **技能扩展** - 可扩展的技能注册系统
- 🔧 **MCP 集成** - 支持 MCP 服务器
- ⏰ **定时任务** - 基于 Cron 的定时任务调度
- 💓 **心跳服务** - 健康检查和心跳检测
- 🌐 **Web Console** - 内置 Web 管理界面

## 📦 快速开始

### 环境要求

- Java 17+
- Maven 3.6+

### 编译构建

```bash
git clone https://github.com/liushenling2001/JobClaw.git
cd JobClaw
mvn clean package -DskipTests
```

### 初始化配置

```bash
java -jar target/jobclaw-1.0.0.jar onboard
```

这会创建配置文件 `~/.jobclaw/config.json` 和工作空间目录。

### 配置 API Key

编辑 `~/.jobclaw/config.json`，添加你的 LLM Provider API Key：

```json
{
  "providers": {
    "dashscope": {
      "apiKey": "sk-your-api-key",
      "apiBase": "https://dashscope.aliyuncs.com/compatible-mode/v1"
    }
  },
  "agent": {
    "model": "qwen-plus",
    "provider": "dashscope"
  }
}
```

**推荐使用 DashScope Coding Plan（兼容 OpenAI 格式）:**
```json
{
  "providers": {
    "dashscope": {
      "apiKey": "sk-your-api-key",
      "apiBase": "https://coding.dashscope.aliyuncs.com"
    }
  },
  "agent": {
    "model": "qwen3.5-plus",
    "provider": "dashscope"
  }
}
```

### 启动服务

**命令行模式:**
```bash
java -jar target/jobclaw-1.0.0.jar agent
```

**网关模式 (支持多通道 + Web Console):**
```bash
java -jar target/jobclaw-1.0.0.jar gateway
```

**访问 Web Console:**
```
http://localhost:18791
```

## 📖 CLI 命令

| 命令 | 描述 |
|------|------|
| `onboard` | 初始化配置和工作空间 |
| `agent` | 与 Agent 交互 (CLI 模式) |
| `gateway` | 启动网关服务 |
| `status` | 显示系统状态 |
| `demo` | 运行演示（如：`demo agent-basic`） |
| `version` | 显示版本 |
| `cron` | 管理定时任务 |
| `skills` | 管理技能 |
| `mcp` | 管理 MCP 服务器 |

**示例:**
```bash
# 运行基础 Agent 演示
java -jar target/jobclaw-1.0.0.jar demo agent-basic

# 查看系统状态
java -jar target/jobclaw-1.0.0.jar status
```

## 🔧 配置说明

### 支持的 LLM Provider

| Provider | 配置项 | 备注 |
|----------|--------|------|
| OpenRouter | `openrouter` | |
| Anthropic | `anthropic` | |
| OpenAI | `openai` | |
| 智谱 AI | `zhipu` | |
| Gemini | `gemini` | |
| 通义千问 | `dashscope` | ✅ 推荐（Coding Plan） |
| Ollama | `ollama` | 本地部署 |

**推荐使用 DashScope Coding Plan（兼容 OpenAI 格式）:**
- 支持通义千问系列模型（qwen-plus, qwen-max, qwen3.5-plus 等）
- 使用 OpenAI 兼容接口，无需特殊适配
- 性价比高，响应速度快

### 端口配置

- **Gateway 端口**: 18791 (Web Console 和 API)
- **MaixCam 端口**: 18790

## 📁 项目结构

```
JobClaw/
├── src/main/java/io/jobclaw/
│   ├── agent/           # Agent 核心逻辑（Spring AI ChatClient）
│   ├── bus/             # 消息总线（MessageBus）
│   ├── channels/        # 通道实现（飞书/钉钉/QQ/Telegram 等）
│   ├── cli/             # CLI 命令（AgentCommand, DemoCommand 等）
│   ├── config/          # 配置管理（Spring Boot Configuration）
│   ├── cron/            # 定时任务调度
│   ├── heartbeat/       # 心跳服务
│   ├── mcp/             # MCP 集成
│   ├── providers/       # LLM Provider（Spring AI Models）
│   ├── security/        # 安全守卫
│   ├── session/         # 会话管理
│   ├── skills/          # 技能系统
│   ├── tools/           # 工具系统（Spring AI @Tool）
│   └── web/             # Web Console（REST API）
├── src/main/resources/
│   ├── application.yml  # Spring Boot 配置
│   └── static/          # 静态资源（HTML/CSS/JS）
├── pom.xml              # Maven 依赖配置
├── MIGRATION_PLAN.md    # Spring AI 迁移文档
└── README.md            # 项目说明
```

**关键文件说明:**
- `AgentLoop.java` - Agent 核心循环，使用 Spring AI ChatClient
- `FileTools.java` - 文件工具，使用 Spring AI @Tool 注解
- `FeishuChannel.java` - 飞书通道，使用官方 SDK（lark-oapi-sdk）
- `MessageBus.java` - 消息总线，连接通道和 Agent
- `application.yml` - Spring AI 和 Spring Boot 配置

## 🎯 使用示例

### 与 Agent 对话

```bash
# 启动 agent 命令
java -jar target/jobclaw-1.0.0.jar agent

# 发送单条消息
java -jar target/jobclaw-1.0.0.jar agent -m "你好，请介绍一下 JobClaw"
```

### 运行演示

```bash
# 单 Agent 演示
java -jar target/jobclaw-1.0.0.jar demo agent-basic

# 多 Agent 协作演示
java -jar target/jobclaw-1.0.0.jar demo agent-multi
```

### 多智能体协作

JobClaw 支持多智能体协作模式，自动协调多个专业 Agent 完成复杂任务。

**预置角色:**
- 👨‍💼 **Assistant** - 通用助手（默认）
- 👨‍💻 **Coder** - 程序员（代码编写和审查）
- 🔬 **Researcher** - 研究员（信息收集和分析）
- ✍️ **Writer** - 作家（文档和内容创作）
- 🔍 **Reviewer** - 审查员（质量检查和验证）
- 📋 **Planner** - 规划师（任务分解和规划）
- 🧪 **Tester** - 测试员（测试和验证）

**使用方式:**

1. **自动多 Agent 模式** - 请求中包含"多智能体"、"协作"、"团队"等关键词
```
请用多智能体协作模式开发一个 Java 工具类...
```

2. **指定角色** - 明确指定 Agent 角色
```
作为程序员，请帮我编写一个 JSON 处理工具...
扮演研究员，分析这个技术方案...
```

3. **命令行演示**
```bash
# 运行多 Agent 协作演示
java -jar target/jobclaw-1.0.0.jar demo agent-multi
```

**协作流程:**
```
用户请求 → Orchestrator 分析
  ↓
Planner 分解任务
  ↓
Executors (Coder/Researcher/Writer/Tester) 执行
  ↓
Reviewer 质量审查
  ↓
Writer 汇总输出
  ↓
用户收到完整结果
```

### 使用工具

Agent 支持以下内置工具：
- `read_file` - 读取文件内容
- `write_file` - 写入文件内容
- `list_dir` - 浏览目录

**示例对话:**
```
用户：读取 /home/user/test.txt 的内容
Agent: [使用 read_file 工具读取文件并返回内容]
```

### 飞书通道配置

**飞书开放平台配置:**
1. 访问 https://open.feishu.cn/app 创建企业自建应用
2. 在"凭证与基础信息"中获取 App ID 和 App Secret
3. 在"事件订阅"中选择"使用长连接接收事件"
4. 订阅 `im.message.receive_v1` 事件
5. 在"机器人"能力中启用机器人

**config.json 配置:**
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

## 🔐 安全

- 命令黑名单保护
- 工作空间隔离
- 安全守卫层
- 通道权限校验

## 🔄 重大更新

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
- ✅ 移除自定义 OkHttp 实现，使用 SDK 内置 API
- ✅ 简化 WebSocket 连接和事件处理

**性能优化:**
- ✅ 修复工具调用循环问题
- ✅ 优化系统提示词
- ✅ 添加 maxTokens 限制

**详细说明请参考:** [MIGRATION_PLAN.md](MIGRATION_PLAN.md)

## 📄 License

MIT License

## 🙏 致谢

- Spring Boot 团队 - Spring Boot 3.3 框架
- Spring AI 团队 - Spring AI 1.0 框架
- 飞书开放平台 - 飞书官方 SDK (lark-oapi-sdk)
- 各 LLM Provider - DashScope、OpenAI、Anthropic 等

## 📬 联系方式

- GitHub Issues: [提交问题](https://github.com/liushenling2001/JobClaw/issues)
- Email: 22607104@qq.com
