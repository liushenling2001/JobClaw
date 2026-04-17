# JobClaw 实时执行状态反馈优化

## 优化内容

本次优化为 JobClaw 添加了 Agent 执行过程中的实时状态反馈功能，前端可以实时看到 Agent 的思考过程和工具执行情况。

## 配置说明

### Agent 配置 (application.yaml 或 application.json)

```yaml
agent:
  workspace: "~/.jobclaw/workspace"
  model: "qwen3.5-plus"
  provider: "dashscope"
  maxTokens: 32768        # LLM 响应最大 token 数
  temperature: 0.7        # LLM 温度参数
  maxToolIterations: 20   # 最大工具调用次数
```

这些配置会被 `AgentLoop` 使用，通过 `OpenAiChatOptions` 传递给 LLM Provider。

### 新增文件

### 1. `ExecutionEvent.java` (执行事件类)
- **位置**: `src/main/java/io/jobclaw/agent/ExecutionEvent.java`
- **功能**: 定义执行过程中的各种事件类型
- **事件类型**:
  - `THINK_START` - Agent 开始思考
  - `THINK_STREAM` - 思考中（流式输出）
  - `THINK_END` - 思考结束
  - `TOOL_START` - 工具调用开始
  - `TOOL_END` - 工具调用结束
  - `TOOL_OUTPUT` - 工具输出
  - `ERROR` - 错误事件
  - `FINAL_RESPONSE` - 最终响应
  - `CUSTOM` - 自定义消息

### 2. `ExecutionTraceService.java` (执行跟踪服务)
- **位置**: `src/main/java/io/jobclaw/agent/ExecutionTraceService.java`
- **功能**: 管理执行过程的实时跟踪和 SSE 推送
- **核心方法**:
  - `subscribe(sessionId, emitter)` - 订阅执行事件
  - `publish(event)` - 发布执行事件
  - `getHistory(sessionId)` - 获取历史事件
  - `clear(sessionId)` - 清除跟踪数据

## 修改文件

### 1. `AgentLoop.java`
- **新增方法**:
  - `processWithDefinition(sessionKey, userContent, definition, eventCallback)` - 带回调的处理方法
  - `process(sessionKey, userContent, eventCallback)` - 带回调的简化方法
  - `process(sessionKey, userContent, role, eventCallback)` - 带角色和回调的方法
- **保持兼容**: 原有方法保持不变，向后兼容

### 2. `AgentOrchestrator.java`
- **新增方法**:
  - `process(sessionKey, userContent, eventCallback)` - 带回调的编排方法
  - `handleSingleAgentDefault(sessionKey, userContent, eventCallback)`
  - `handleSingleAgentWithRole(sessionKey, userContent, role, eventCallback)`
  - `handleTeamMode(sessionKey, userContent, eventCallback)`
- **保持兼容**: 原有方法保持不变，通道管理不受影响

### 3. `WebConsoleController.java`
- **新增依赖注入**: `ExecutionTraceService`
- **新增端点**:
  - `POST /api/execute/stream` - 执行任务并订阅执行过程事件
  - `GET /api/execute/stream/{sessionKey}` - 只读订阅执行事件
- **兼容入口**: 保留 `/api/chat`；流式执行统一使用 `/api/execute/stream`

### 4. `index.html` (前端页面)
- **新增功能**:
  - 模式切换按钮（普通模式 / 流式输出模式）
  - 执行事件样式（不同类型事件不同颜色）
  - SSE 流式接收逻辑
- **视觉效果**:
  - 思考事件 - 蓝色边框
  - 工具事件 - 紫色边框
  - 错误事件 - 红色边框
  - 最终响应 - 绿色边框

## 架构设计

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Frontend (SSE) │────▶│ ExecutionTrace   │────▶│  AgentLoop      │
│                 │◀────│ Service          │◀────│  (with callback)│
└─────────────────┘     └──────────────────┘     └─────────────────┘
                               │
                               ▼
                        ┌──────────────────┐
                        │ AgentOrchestrator│
                        └──────────────────┘
```

## 使用方式

### 1. 普通模式（向后兼容）
```javascript
fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: 'Hello', sessionKey: 'web:123' })
})
```

### 2. 流式输出模式（新功能）
```javascript
fetch('/api/execute/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: 'Hello', sessionKey: 'web:123' })
})
```

### 3. 只读订阅（多客户端场景）
```javascript
const eventSource = new EventSource('/api/execute/stream/web:123');
eventSource.onmessage = (event) => {
    const data = JSON.parse(event.data);
    console.log('Event:', data.type, data.content);
};
```

## 通道管理兼容性

**重要**: 所有修改都保持了向后兼容性：

1. **ChannelManager** - 不受影响，继续使用原有的 `MessageBus` 机制
2. **AgentOrchestrator.process()** - 原有方法签名不变，通道管理调用不受影响
3. **AgentLoop.process()** - 原有方法保留，不带回调的调用不受影响

新增的流式功能仅影响 Web 控制台，通道（DingTalk、Discord 等）的消息处理保持不变。

## 事件类型说明

| 事件类型 | 说明 | 颜色 |
|---------|------|------|
| THINK_START | Agent 开始思考 | 蓝色 |
| THINK_END | 思考完成 | 蓝色 |
| TOOL_START | 工具调用开始 | 紫色 |
| TOOL_END | 工具调用结束 | 紫色 |
| TOOL_OUTPUT | 工具执行输出 | 灰色 |
| ERROR | 错误事件 | 红色 |
| FINAL_RESPONSE | 最终响应 | 绿色 |
| CUSTOM | 自定义消息 | 淡紫色 |

## 编译验证

```bash
cd D:/workspace/jobclaw/JobClaw
mvn compile
# BUILD SUCCESS
```

## 后续优化建议

1. **工具级别回调** - 在 `FileTools`、`ExecTool` 等工具执行时发布更详细的事件
2. **Token 使用统计** - 实时显示 Token 使用情况
3. **执行进度条** - 显示任务执行的进度百分比
4. **多客户端同步** - 多个前端客户端可以同时查看同一 Agent 的执行状态
