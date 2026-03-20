# JobClaw 🦞

**AI Agent Framework based on Spring Boot 3.3 + Java 17**

JobClaw 是一个轻量级的 AI Agent 框架，基于 Spring Boot 3.3 和 Java 17 构建，支持多通道消息接入、工具系统、技能扩展等功能。

## ✨ 特性

- 🚀 **Spring Boot 3.3** - 现代化的微服务框架
- 💬 **多通道支持** - Telegram, Discord, 钉钉，飞书，QQ, WhatsApp
- 🛠️ **工具系统** - 文件读写、目录浏览等内置工具
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
      "apiBase": "https://dashscope.aliyuncs.com/v1"
    }
  },
  "agent": {
    "model": "qwen-plus",
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
| `demo` | 运行演示 |
| `version` | 显示版本 |
| `cron` | 管理定时任务 |
| `skills` | 管理技能 |
| `mcp` | 管理 MCP 服务器 |

## 🔧 配置说明

### 支持的 LLM Provider

| Provider | 配置项 |
|----------|--------|
| OpenRouter | `openrouter` |
| Anthropic | `anthropic` |
| OpenAI | `openai` |
| 智谱 AI | `zhipu` |
| Gemini | `gemini` |
| 通义千问 | `dashscope` |
| Ollama | `ollama` |

### 端口配置

- **Gateway 端口**: 18791 (Web Console 和 API)
- **MaixCam 端口**: 18790

## 📁 项目结构

```
JobClaw/
├── src/main/java/io/jobclaw/
│   ├── agent/           # Agent 核心逻辑
│   ├── bus/             # 消息总线
│   ├── channels/        # 通道实现
│   ├── cli/             # CLI 命令
│   ├── config/          # 配置管理
│   ├── cron/            # 定时任务
│   ├── heartbeat/       # 心跳服务
│   ├── mcp/             # MCP 集成
│   ├── providers/       # LLM Provider
│   ├── security/        # 安全守卫
│   ├── session/         # 会话管理
│   ├── skills/          # 技能系统
│   ├── tools/           # 工具系统
│   └── web/             # Web Console
├── src/main/resources/
│   ├── application.yml  # Spring 配置
│   └── static/          # 静态资源
└── pom.xml
```

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
java -jar target/jobclaw-1.0.0.jar demo agent-basic
```

## 🔐 安全

- 命令黑名单保护
- 工作空间隔离
- 安全守卫层

## 📄 License

MIT License

## 🙏 致谢

- Spring Boot 团队
- 各 LLM Provider

## 📬 联系方式

- GitHub Issues: [提交问题](https://github.com/liushenling2001/JobClaw/issues)
- Email: 22607104@qq.com
