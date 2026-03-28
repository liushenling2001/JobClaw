# JobClaw 👨‍🔧

**AI Agent Framework based on Spring Boot 3.3 + Java 17 + Spring AI**

JobClaw 是一个轻量级、可扩展的 AI Agent 框架，基于 Spring Boot 3.3、Java 17 和 Spring AI 1.0 构建。支持多智能体协作、多通道消息接入、工具系统、技能扩展等功能，配备现代化的 Vue 3 Web 管理界面，适用于构建企业级 AI 助手和自动化应用。

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

### 🌐 现代化 Web Console

**技术栈：**
- **Vite 6** - 快速开发和构建工具
- **Vue 3.4** - Composition API + TypeScript
- **Vue Router 4** - 页面路由管理
- **Pinia 2** - 状态管理
- **Tailwind CSS 3** - 赛博朋克风格设计系统
- **Axios** - HTTP 客户端

**管理页面（14 个）：**

| 页面 | 路由 | 功能 |
|------|------|------|
| Dashboard | `/dashboard` | 系统概览和快速操作 |
| Chat | `/chat` | AI 对话，支持 SSE 流式输出和历史会话 |
| Sessions | `/sessions` | 历史会话列表 |
| SessionDetail | `/sessions/:id` | 查看会话详情和消息历史 |
| Channels | `/channels` | 通道管理和配置 |
| Providers | `/providers` | LLM Provider 配置和 API Key 管理 |
| Models | `/models` | 模型列表和当前模型切换 |
| AgentConfig | `/agent` | Agent 基础配置（maxTokens, temperature 等） |
| CronJobs | `/cron` | 定时任务管理 |
| Skills | `/skills` | 技能浏览/创建/编辑 |
| MCPServers | `/mcp` | MCP 服务器配置 |
| WorkspaceFiles | `/files` | 工作空间文件管理 |
| TokenStats | `/stats` | Token 使用统计和图表 |
| Settings | `/settings` | 系统设置和认证管理 |

**核心功能：**
- ✅ **SSE 流式输出** - 实时显示 AI 思考和工具调用过程
- ✅ **历史会话** - 完整的会话管理和消息查看
- ✅ **工具调用面板** - 可视化工具调用状态和结果
- ✅ **响应式设计** - 赛博朋克风格深色主题
- ✅ **错误处理** - 完整的通知和错误提示系统
- ✅ **认证登录** - 简单模式用户名/密码登录

访问地址：`http://localhost:18791`

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
- **Node.js**: 18+ (前端开发)
- **操作系统**: Linux / macOS / Windows

### 编译构建

```bash
# 克隆仓库
git clone https://github.com/liushenling2001/JobClaw.git
cd JobClaw

# 后端编译打包
mvn clean package -DskipTests

# 前端构建（开发时可选）
cd ui
npm install
npm run build
# 构建产物自动输出到 src/main/resources/static/
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
- `read_file` - 读取任意文本文件
- `write_file` - 写入/创建文件
- `list_dir` - 浏览目录内容
- `edit_file` - 精确文本替换
- `append_file` - 追加内容到文件末尾

#### 文档处理工具 (2 个)
- `read_word` - 读取 Word 文档 (.doc/.docx)
- `read_excel` - 读取 Excel 表格 (.xls/.xlsx)

#### 命令执行工具 (1 个)
- `exec` - 执行 Shell 命令

#### 网络工具 (2 个)
- `web_search` - 网页搜索 (Brave API)
- `web_fetch` - 抓取网页内容

#### 系统工具 (3 个)
- `cron` - 定时任务管理
- `message` - 发送消息到 IM 通道
- `query_token_usage` - 查询 Token 使用统计

#### MCP 工具 (1 个)
- `mcp` - Model Context Protocol

---

## 💻 前端开发

### 目录结构

```
ui/
├── src/
│   ├── api/              # API 服务层
│   │   ├── agent.ts
│   │   ├── channels.ts
│   │   ├── chat.ts
│   │   ├── cron.ts
│   │   ├── files.ts
│   │   ├── mcp.ts
│   │   ├── models.ts
│   │   ├── providers.ts
│   │   ├── sessions.ts
│   │   ├── skills.ts
│   │   ├── stats.ts
│   │   └── index.ts      # Axios 配置
│   ├── components/       # Vue 组件
│   │   ├── common/       # 通用组件
│   │   │   ├── Button.vue
│   │   │   ├── Card.vue
│   │   │   ├── Input.vue
│   │   │   ├── Modal.vue
│   │   │   └── Skeleton.vue
│   │   ├── Chat/         # Chat 相关组件
│   │   │   ├── ChatWindow.vue
│   │   │   ├── MessageInput.vue
│   │   │   ├── MessageList.vue
│   │   │   └── ToolCallPanel.vue
│   │   └── Layout/       # 布局组件
│   │       └── AppLayout.vue
│   ├── composables/      # 组合式函数
│   │   ├── useChatStream.ts   # SSE 流式输出
│   │   └── useToast.ts        # Toast 通知
│   ├── router/           # 路由配置
│   ├── stores/           # Pinia 状态管理
│   │   ├── auth.ts
│   │   ├── chat.ts
│   │   ├── index.ts
│   ├── types/            # TypeScript 类型定义
│   │   └── index.ts
│   ├── views/            # 页面组件
│   │   ├── AgentConfig.vue
│   │   ├── Channels.vue
│   │   ├── Chat.vue
│   │   ├── CronJobs.vue
│   │   ├── Dashboard.vue
│   │   ├── Login.vue
│   │   ├── MCPServers.vue
│   │   ├── Models.vue
│   │   ├── Providers.vue
│   │   ├── SessionDetail.vue
│   │   ├── Sessions.vue
│   │   ├── Settings.vue
│   │   ├── Skills.vue
│   │   ├── TokenStats.vue
│   │   └── WorkspaceFiles.vue
│   └── App.vue
├── package.json
├── tsconfig.json
├── vite.config.ts
└── tailwind.config.js
```

### 开发模式

```bash
cd ui
npm install
npm run dev
# 访问 http://localhost:5173
# API 代理到 http://localhost:8080
```

### 构建生产版本

```bash
cd ui
npm run build
# 输出到 ../src/main/resources/static/
```

### 技术要点

**SSE 流式输出实现：**
```typescript
// composables/useChatStream.ts
const startStream = async (message: string) => {
  const response = await fetch('/api/execute/stream', {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${token}` },
    body: JSON.stringify({ message, sessionKey })
  });

  const reader = response.body.getReader();
  const decoder = new TextDecoder();

  while (true) {
    const { done, value } = await reader.read();
    const chunk = decoder.decode(value);
    // 解析 SSE 事件: THINK_STREAM, TOOL_CALL, FINAL_RESPONSE...
  }
};
```

**Pinia 状态管理：**
```typescript
// stores/chat.ts
export const useChatStore = defineStore('chat', {
  state: () => ({
    currentSessionKey: string | null,
    messages: Message[],
    isStreaming: boolean,
    isSidebarOpen: boolean
  }),
  actions: {
    addMessage(role, content, toolCall?) { ... },
    createNewSession() { ... }
  }
});
```

---

## 📖 CLI 命令

| 命令 | 描述 |
|------|------|
| `onboard` | 初始化配置和工作空间 |
| `agent` | 与 Agent 交互（CLI 模式） |
| `gateway` | 启动网关服务 |
| `status` | 显示系统状态 |
| `demo` | 运行演示 |
| `version` | 显示版本信息 |
| `cron` | 管理定时任务 |
| `skills` | 管理技能 |
| `mcp` | 管理 MCP 服务器 |

---

## 🔧 配置说明

### LLM Provider 配置

```json
{
  "providers": {
    "dashscope": { "apiKey": "sk-xxx", "apiBase": "https://coding.dashscope.aliyuncs.com" },
    "openai": { "apiKey": "sk-xxx", "apiBase": "https://api.openai.com" },
    "anthropic": { "apiKey": "sk-xxx" },
    "zhipu": { "apiKey": "xxx" },
    "gemini": { "apiKey": "xxx" },
    "ollama": { "apiBase": "http://localhost:11434" }
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
│   ├── channels/           # 通道实现
│   ├── cli/                # CLI 命令
│   ├── config/             # 配置管理
│   ├── cron/               # 定时任务
│   ├── heartbeat/          # 心跳服务
│   ├── mcp/                # MCP 集成
│   ├── providers/          # LLM Provider
│   ├── security/           # 安全守卫
│   ├── session/            # 会话管理
│   ├── skills/             # 技能系统
│   ├── tools/              # 工具系统
│   └── web/                # Web Console
├── ui/                     # Vue 3 前端
│   ├── src/
│   │   ├── api/
│   │   ├── components/
│   │   ├── composables/
│   │   ├── router/
│   │   ├── stores/
│   │   ├── types/
│   │   └── views/
│   ├── package.json
│   └── vite.config.ts
├── src/main/resources/
│   ├── application.yml
│   └── static/             # 前端构建产物
├── pom.xml
└── README.md
```

---

## 🎯 使用示例

### Web Console

1. 访问 `http://localhost:18791`
2. 登录（如果需要）
3. 在 Chat 页面与 AI 对话
4. 查看历史会话、配置 Provider、管理通道等

### API 调用

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

### v1.0.0 - Spring AI 迁移 + 多智能体 + Vue 3 前端 (2026-03-28)

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
- ✅ 支持角色指定

**前端重构:**
- ✅ 从单文件 HTML 重构为 Vue 3 + TypeScript 架构
- ✅ 使用 Vite 6 + Vue 3.4 + Pinia + Vue Router 4
- ✅ 14 个管理页面完整实现
- ✅ SSE 流式输出和历史会话管理
- ✅ 赛博朋克风格设计系统

---

## 🔐 安全

- 命令黑名单
- 工作空间隔离
- 安全守卫层
- 通道权限校验

---

## 📄 License

MIT License

---

## 🙏 致谢

- **Spring Boot 团队** - Spring Boot 3.3 框架
- **Spring AI 团队** - Spring AI 1.0 框架
- **飞书开放平台** - 飞书官方 SDK (lark-oapi-sdk)
- **Vue 团队** - Vue 3 框架
- **各 LLM Provider** - DashScope、OpenAI、Anthropic、智谱 AI、Gemini、Ollama

---

## 📬 联系方式

- **GitHub Issues**: [提交问题](https://github.com/liushenling2001/JobClaw/issues)
- **GitHub 仓库**: https://github.com/liushenling2001/JobClaw
- **Email**: 22607104@qq.com

---

**JobClaw** - 构建智能、协作、可扩展的 AI 应用 👨‍🔧🤖
