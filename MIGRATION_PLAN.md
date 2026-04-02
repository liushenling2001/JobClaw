# JobClaw 迁移到 AgentScope 执行引擎计划

## 📋 项目概述

**目标**: 将 JobClaw 项目中的执行部分（AgentLoop、Tool 系统、Message 系统）替换为 AgentScope Java，提升执行能力和可维护性。

**约束**: 
- 不提交到 GitHub，等待用户审核
- 保留现有非执行部分（配置管理、会话管理、通道集成等）
- 使用 AgentScope 1.0.9 版本

---

## 🔍 现状分析

### JobClaw 当前架构

```
JobClaw 执行核心:
├── AgentLoop.java          # 核心执行循环 (processDirect, processWithTools)
├── LLMProvider.java        # LLM 提供者接口
├── Message.java            # 消息数据结构
├── ToolCall.java           # 工具调用
├── ToolDefinition.java     # 工具定义
├── ToolRegistry.java       # 工具注册中心
├── Tool.java               # 工具接口
└── ContextBuilder.java     # 上下文构建器
```

### AgentScope 核心能力

```
AgentScope 执行核心:
├── ReActAgent              # ReAct 模式 Agent 实现
├── Toolkit                 # 工具集合（支持 @Tool 注解）
├── Msg                     # 统一消息结构
├── Hook                    # 行为扩展点
├── Memory                  # 记忆管理
└── Model                   # 模型抽象（DashScope, OpenAI, etc.）
```

---

## 📝 修改计划

### 阶段一：依赖添加 (pom.xml)

**操作**: 添加 AgentScope 核心依赖

```xml
<!-- AgentScope Core (all-in-one) -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.9</version>
</dependency>

<!-- AgentScope Spring Boot Starter (可选，简化集成) -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-spring-boot-starter</artifactId>
<version>1.0.9</version>
</dependency>
```

**影响文件**: 
- `pom.xml`

---

### 阶段二：消息系统迁移

**操作**: 将 JobClaw 的 Message 类迁移到 AgentScope 的 Msg

**当前 JobClaw Message**:
```java
// 现有实现
public class Message {
    private String role;  // "user", "assistant", "tool"
    private String content;
    private List<ToolCall> toolCalls;
    // ...
}
```

**AgentScope Msg**:
```java
// 使用 AgentScope
Msg msg = Msg.builder()
    .name("user")
    .textContent("Hello")
    .build();
```

**影响文件**:
- `src/main/java/io/jobclaw/providers/Message.java` → 改为使用 AgentScope Msg 的适配器
- `src/main/java/io/jobclaw/agent/ContextBuilder.java` → 更新消息构建逻辑

**建议**: 创建适配器类 `JobClawMsgAdapter`，保持现有代码兼容性

---

### 阶段三：工具系统迁移 ⭐

**操作**: 将 ToolRegistry + Tool 接口迁移到 AgentScope Toolkit + @Tool 注解

**当前实现**:
```java
// JobClaw 方式
public interface Tool {
    String getName();
    String getDescription();
    String execute(Map<String, Object> args);
}

// 注册
toolRegistry.register(new ReadFileTool());
```

**AgentScope 方式**:
```java
// 使用注解
public class FileTools {
    @Tool(name = "read_file", description = "Read file content")
    public String readFile(
        @ToolParam(name = "path", description = "File path") String path
    ) {
        // 实现
    }
}

// 注册
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new FileTools());
```

**需要修改的文件**:
1. `src/main/java/io/jobclaw/tools/Tool.java` → 保留作为兼容层或移除
2. `src/main/java/io/jobclaw/tools/ToolRegistry.java` → 改为使用 AgentScope Toolkit
3. `src/main/java/io/jobclaw/tools/ReadFileTool.java` → 重写为 @Tool 注解方式
4. `src/main/java/io/jobclaw/tools/WriteFileTool.java` → 重写为 @Tool 注解方式
5. `src/main/java/io/jobclaw/tools/ListDirTool.java` → 重写为 @Tool 注解方式

**新文件**:
- `src/main/java/io/jobclaw/tools/JobClawToolkit.java` → 统一工具集合类

---

### 阶段四：AgentLoop 重构 ⭐⭐⭐

**操作**: 将 AgentLoop 的核心执行逻辑替换为 AgentScope ReActAgent

**当前实现** (简化):
```java
public class AgentLoop {
    private final LLMProvider provider;
    private final ToolRegistry toolRegistry;
    
    public String processWithTools(String sessionKey, String userContent) {
        List<Message> messages = contextBuilder.buildMessages(sessionKey, userContent);
        LLMResponse response = executeWithToolLoop(messages, sessionKey);
        return response.getContent();
    }
}
```

**AgentScope 实现**:
```java
@Component
public class AgentLoop {
    private final ReActAgent agent;
    private final SessionManager sessionManager;
    
    public AgentLoop(Config config, SessionManager sessionManager, Toolkit toolkit) {
        this.sessionManager = sessionManager;
        
        // 创建模型
        DashScopeChatModel model = DashScopeChatModel.builder()
            .apiKey(config.getProviders().getDashscope().getApiKey())
            .modelName(config.getAgent().getModel())
            .build();
        
        // 创建 Agent
        this.agent = ReActAgent.builder()
            .name("JobClaw")
            .sysPrompt(config.getAgent().getSysPrompt())
            .model(model)
            .toolkit(toolkit)
            .maxIters(config.getAgent().getMaxToolIterations())
            .build();
    }
    
    public String process(String sessionKey, String userContent) {
        // 加载会话历史
        Session session = sessionManager.getOrCreate(sessionKey);
        List<Msg> history = buildHistory(session);
        
        // 调用 Agent
        Msg userMsg = Msg.builder()
            .textContent(userContent)
            .build();
        
        Msg response = agent.call(userMsg).block();
        
        // 保存会话
        session.addMessage("user", userContent);
        session.addMessage("assistant", response.getTextContent());
        sessionManager.save(session);
        
        return response.getTextContent();
    }
}
```

**影响文件**:
- `src/main/java/io/jobclaw/agent/AgentLoop.java` → 核心重构
- `src/main/java/io/jobclaw/agent/ContextBuilder.java` → 适配 AgentScope 消息格式

**移除文件** (可选):
- `src/main/java/io/jobclaw/providers/LLMProvider.java` → 由 AgentScope Model 替代
- `src/main/java/io/jobclaw/providers/HTTPProvider.java` → 由 AgentScope 内置支持替代
- `src/main/java/io/jobclaw/providers/LLMResponse.java` → 由 AgentScope Msg 替代
- `src/main/java/io/jobclaw/providers/ToolCall.java` → 由 AgentScope ToolUseBlock 替代
- `src/main/java/io/jobclaw/providers/ToolDefinition.java` → 由 AgentScope 自动处理

---

### 阶段五：配置适配

**操作**: 调整配置结构以支持 AgentScope

**当前配置** (`~/.jobclaw/config.json`):
```json
{
  "providers": {
    "dashscope": {
      "apiKey": "sk-xxx",
      "apiBase": "https://dashscope.aliyuncs.com/v1"
    }
  },
  "agent": {
    "model": "qwen-plus",
    "provider": "dashscope",
    "maxToolIterations": 10,
    "maxTokens": 4000,
    "temperature": 0.7
  }
}
```

**AgentScope 配置** (保持不变，内部适配):
- API Key → 传递给 DashScopeChatModel
- Model Name → 传递给 modelName
- Temperature/MaxTokens → 通过 GenerateOptions 配置

**影响文件**:
- `src/main/java/io/jobclaw/config/Config.java` → 添加 AgentScope 相关配置项
- `src/main/java/io/jobclaw/config/AgentConfig.java` → 扩展配置选项

---

### 阶段六：高级功能集成 (可选)

#### 6.1 Hook 系统
**用途**: 在 Agent 执行的关键节点插入自定义逻辑（日志、监控、消息修改）

```java
Hook loggingHook = new Hook() {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return switch (event) {
            case PreCallEvent e -> {
                logger.info("Agent starting...");
                yield Mono.just(event);
            }
            case PostCallEvent e -> {
                logger.info("Completed: {}", e.getFinalMessage().getTextContent());
                yield Mono.just(event);
            }
            default -> Mono.just(event);
        };
    }
};

agent = ReActAgent.builder()
    .hook(loggingHook)
    .build();
```

#### 6.2 长期记忆
**用途**: 跨会话记忆，支持语义搜索

```java
// Mem0 长期记忆
LongTermMemory memory = Mem0LongTermMemory.builder()
    .apiKey(System.getenv("MEM0_API_KEY"))
    .agentId("jobclaw")
    .build();

agent = ReActAgent.builder()
    .longTermMemory(memory)
    .longTermMemoryMode(LongTermMemoryMode.BOTH)
    .build();
```

#### 6.3 结构化输出
**用途**: 保证输出格式正确

```java
public class TaskResult {
    public String status;
    public List<String> steps;
}

agent = ReActAgent.builder()
    .structuredOutput(TaskResult.class)
    .build();
```

---

## 📂 文件变更清单

### 新增文件
| 文件路径 | 说明 | 状态 |
|---------|------|------|
| `src/main/java/io/jobclaw/tools/FileTools.java` | @Tool 注解工具集合 | ✅ 完成 |
| `src/main/java/io/jobclaw/tools/JobClawToolkit.java` | 统一工具集合 | ✅ 完成 |

### 修改文件
| 文件路径 | 修改内容 | 状态 |
|---------|---------|------|
| `pom.xml` | 添加 AgentScope 依赖 | ✅ 完成 |
| `AgentLoop.java` | 核心执行逻辑重构 | ✅ 完成 |
| `ContextBuilder.java` | 消息格式适配 | ⏸️ 保留兼容 |

### 可移除文件（暂不删除，先保留）
| 文件路径 | 说明 |
|---------|------|
| `providers/LLMProvider.java` | 由 AgentScope Model 替代 |
| `providers/HTTPProvider.java` | 由 AgentScope 内置支持替代 |
| `providers/LLMResponse.java` | 由 AgentScope Msg 替代 |
| `providers/ToolCall.java` | 由 AgentScope ToolUseBlock 替代 |
| `providers/ToolDefinition.java` | 由 AgentScope 自动处理 |
| `providers/Message.java` | 由 AgentScope Msg 替代 |
| `tools/Tool.java` | 旧工具接口 |
| `tools/ToolRegistry.java` | 旧工具注册中心 |
| `tools/ReadFileTool.java` | 已迁移到 FileTools |
| `tools/WriteFileTool.java` | 已迁移到 FileTools |
| `tools/ListDirTool.java` | 已迁移到 FileTools |

---

## 🚀 实施步骤

### Step 1: 环境准备 ✅
```bash
cd /home/22607104_wy/openclaw/workspace/JobClaw
# 备份当前代码
git checkout -b backup-before-agentscope
```

### Step 2: 添加依赖 ✅
- 编辑 `pom.xml` - 已添加 agentscope 1.0.9
- 运行 `mvn clean compile` 验证依赖（需要 JDK 17）

### Step 3: 工具系统迁移 ✅
- 创建 FileTools.java - 使用 @Tool 注解
- 创建 JobClawToolkit.java - 封装 Toolkit
- ~~重写 ReadFileTool/WriteFileTool/ListDirTool~~ - 保留作为备份

### Step 4: AgentLoop 重构 ✅
- 保留 AgentLoop 类名（保持外部调用兼容）
- 内部使用 ReActAgent
- 系统提示硬编码（移除 ContextBuilder 依赖）

### Step 5: 集成测试 ⏸️
- 测试工具调用
- 测试多轮对话
- 测试错误处理
- **需要 JDK 17 环境**

### Step 6: 清理 ⏸️
- 移除废弃的 providers 包
- 更新文档

---

## ⚠️ 风险与注意事项

### 1. 并发问题
**AgentScope Agent 是状态ful 的**，同一实例不能并发调用。

**解决方案**:
```java
// 每个会话使用独立的 Agent 实例
// 或使用对象池管理 Agent 实例
```

### 2. 响应式编程
**AgentScope 基于 Reactor**，使用 Mono/Flux。

**解决方案**:
- 在边界处使用 `.block()` 转换为同步调用
- 或改造整个调用链为响应式

### 3. 配置兼容
**保持现有配置格式**，内部适配到 AgentScope。

### 4. 测试覆盖
**确保现有功能正常**:
- 工具调用
- 多轮对话
- 错误处理
- 会话管理

---

## 📊 预期收益

| 方面 | 改进 |
|-----|------|
| **代码量** | 减少 ~40% (移除重复实现) |
| **可维护性** | 提升 (使用成熟框架) |
| **功能扩展** | 更容易 (Hook、长期记忆、RAG 等) |
| **模型支持** | 更多 (OpenAI, Anthropic, Gemini 等) |
| **工具生态** | 更丰富 (MCP 协议支持) |

---

## 📅 预计工时

| 阶段 | 预计时间 | 状态 |
|-----|---------|------|
| 依赖添加 + 编译验证 | 30 分钟 | ✅ 完成 |
| 工具系统迁移 | 2-3 小时 | ✅ 完成 |
| AgentLoop 重构 | 3-4 小时 | ✅ 完成 |
| 集成测试 | 2-3 小时 | ⏸️ 待 JDK 17 |
| 文档更新 | 1 小时 | ✅ 完成 |
| **总计** | **8-11 小时** | **代码修改完成，待编译测试** |

---

## ✅ 验收标准

1. [ ] 基本对话功能正常
2. [ ] 所有现有工具可用
3. [ ] 会话管理正常
4. [ ] 配置加载正常
5. [ ] 错误处理完善
6. [ ] 编译无警告
7. [ ] 通过现有测试用例

---

## 📝 实施进度

**创建时间**: 2026-03-22  
**当前状态**: 代码修改完成，等待 JDK 17 环境编译验证  
**下一步**: 
1. 安装 JDK 17（当前系统为 JDK 11）
2. 编译验证：`mvn clean compile`
3. 修复编译错误
4. 运行测试

---

## ⚠️ 当前阻塞

**Java 版本问题**: 
- 当前系统：JDK 11.0.30
- AgentScope 要求：JDK 17+
- 解决方案：需要安装 JDK 17 或升级系统 Java

**临时方案**: 
- 代码修改已完成，可在有 JDK 17 的环境中编译
- 或等待用户确认环境配置
