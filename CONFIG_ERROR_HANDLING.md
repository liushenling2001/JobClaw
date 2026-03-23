# JobClaw 配置错误处理文档

## 概述

JobClaw 现在具备完善的配置验证和错误处理能力，确保在各种配置问题下都能优雅地处理而不是直接崩溃。

---

## 错误场景及处理

### 1️⃣ 配置文件不存在

**场景：** 首次运行，没有配置文件

**处理：**
```
ℹ️  配置文件不存在，将使用默认配置
```

**解决方案：**
```bash
java -jar jobclaw.jar onboard
```

---

### 2️⃣ JSON 格式错误

**场景：** 配置文件 JSON 语法错误（缺少逗号、引号等）

**错误示例：**
```json
{
  "agent": {
    "workspace": "/tmp/jobclaw"
    "provider": "dashscope"  // 缺少逗号
  }
}
```

**错误输出：**
```
⚠️  配置文件加载失败：Unexpected character ('"' (code 34)): 
   was expecting comma to separate Object entries

可能原因：
  • JSON 格式错误（缺少逗号、引号等）
  • 配置文件编码问题
  • 配置文件权限问题

建议：
  1. 检查配置文件 JSON 格式：cat ~/.jobclaw/config.json
  2. 使用 JSON 验证工具：https://jsonlint.com/
  3. 重新生成配置：jobclaw onboard

将使用默认配置继续启动...
```

**解决方案：**
1. 使用 `cat ~/.jobclaw/config.json` 查看配置
2. 访问 https://jsonlint.com/ 验证 JSON 格式
3. 修复错误或重新运行 `jobclaw onboard`

---

### 3️⃣ API Key 为空

**场景：** Provider 的 API Key 未填写

**配置示例：**
```json
{
  "providers": {
    "dashscope": {
      "apiKey": ""
    }
  }
}
```

**错误输出：**
```
⚠️  配置验证警告：Provider 'dashscope' 的 API Key 未配置

服务仍可启动，但部分功能可能无法使用。

建议修复配置：
  1. 编辑配置文件：nano ~/.jobclaw/config.json
  2. 参考配置示例：https://github.com/liushenling2001/JobClaw#配置
  3. 重新生成配置：jobclaw onboard
```

**解决方案：**
1. 获取 API Key：https://dashscope.console.aliyun.com/apiKey
2. 编辑配置文件填入 API Key
3. 重启服务

---

### 4️⃣ API Key 格式错误

**场景：** API Key 格式不符合要求

**配置示例：**
```json
{
  "providers": {
    "dashscope": {
      "apiKey": "invalid-key-format"  // 应该以 sk- 开头
    }
  }
}
```

**错误输出：**
```
⚠️  配置验证警告：DashScope API Key 应该以 'sk-' 开头，请检查配置
```

**解决方案：**
- DashScope API Key 格式：`sk-xxxxxxxxxxxxxxxx`
- OpenAI API Key 格式：`sk-xxxxxxxxxxxxxxxx`
- 重新获取正确的 API Key

---

### 5️⃣ Provider 未配置

**场景：** 选择了某个 Provider 但没有配置

**配置示例：**
```json
{
  "agent": {
    "provider": "dashscope"
  },
  "providers": {
    // dashscope 配置缺失
  }
}
```

**错误输出：**
```
⚠️  配置验证警告：Provider 'dashscope' 未在 providers 中配置
```

**解决方案：**
```json
{
  "providers": {
    "dashscope": {
      "apiKey": "sk-xxx",
      "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1"
    }
  }
}
```

---

### 6️⃣ 工作空间未配置

**场景：** agent.workspace 为空

**配置示例：**
```json
{
  "agent": {
    "workspace": ""
  }
}
```

**错误输出：**
```
⚠️  配置验证警告：工作空间路径未配置 (agent.workspace)
```

**解决方案：**
```json
{
  "agent": {
    "workspace": "/home/user/.jobclaw/workspace"
  }
}
```

---

### 7️⃣ 网关端口无效

**场景：** 端口号超出有效范围

**配置示例：**
```json
{
  "gateway": {
    "port": 99999  // 超出 1-65535 范围
  }
}
```

**错误输出：**
```
⚠️  配置验证警告：网关端口必须在 1-65535 之间，当前值：99999
```

**解决方案：**
```json
{
  "gateway": {
    "port": 18791  // 使用有效端口
  }
}
```

---

##  degraded 模式（降级运行）

即使配置有错误，JobClaw 仍会以**降级模式**启动：

### 可用功能
- ✅ Web Console 访问
- ✅ 通道管理（Feishu、WhatsApp 等）
- ✅ 定时任务服务
- ✅ 心跳服务
- ✅ 配置管理界面

### 受限功能
- ❌ LLM 对话（需要 API Key）
- ❌ 智能体工具调用（需要 Provider）
- ❌ 多智能体协作（需要完整配置）

---

## 配置验证流程

```
启动 gateway
  ↓
加载配置文件
  ↓
文件不存在？ → 使用默认配置 + 提示 onboard
  ↓
JSON 解析失败？ → 显示详细错误 + 建议 + 使用默认配置
  ↓
验证配置内容
  ├─ 工作空间为空？ → 警告
  ├─ Provider 未配置？ → 警告
  ├─ API Key 为空？ → 警告
  ├─ API Key 格式错误？ → 警告
  └─ 端口无效？ → 警告
  ↓
显示所有警告 + 修复建议
  ↓
启动服务（降级模式）
  ↓
用户可通过 Web Console 修复配置
```

---

## 快速修复指南

### 方法 1：使用 onboard 重新生成

```bash
# 备份旧配置（可选）
cp ~/.jobclaw/config.json ~/.jobclaw/config.json.backup

# 重新生成配置
java -jar jobclaw.jar onboard

# 编辑新生成的配置
nano ~/.jobclaw/config.json

# 填入 API Key 并重启
java -jar jobclaw.jar gateway
```

---

### 方法 2：手动修复配置

```bash
# 1. 查看当前配置
cat ~/.jobclaw/config.json

# 2. 验证 JSON 格式
# 访问 https://jsonlint.com/ 粘贴配置内容

# 3. 编辑配置
nano ~/.jobclaw/config.json

# 4. 确保包含必需字段：
{
  "agent": {
    "workspace": "/home/user/.jobclaw/workspace",
    "provider": "dashscope",
    "model": "qwen3.5-plus"
  },
  "providers": {
    "dashscope": {
      "apiKey": "sk-你的 API Key",
      "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1"
    }
  },
  "gateway": {
    "port": 18791
  }
}

# 5. 重启服务
pkill -f jobclaw
java -jar jobclaw.jar gateway
```

---

### 方法 3：通过 Web Console 修复

1. 启动服务（即使有警告也能启动）
   ```bash
   java -jar jobclaw.jar gateway
   ```

2. 访问 Web Console
   ```
   http://127.0.0.1:18791
   ```

3. 进入 Settings → Models

4. 配置 LLM Provider 和 API Key

5. 保存并测试连接

---

## 技术实现

### Config.java - 验证逻辑

```java
public Optional<String> validate() {
    // 检查工作空间
    if (!isValidWorkspace()) {
        return Optional.of("工作空间路径未配置 (agent.workspace)");
    }
    
    // 检查 LLM Provider 配置
    String validationError = validateProviders();
    if (validationError != null) {
        return Optional.of(validationError);
    }
    
    // 检查网关配置
    if (gateway != null) {
        int port = gateway.getPort();
        if (port < 1 || port > 65535) {
            return Optional.of("网关端口必须在 1-65535 之间，当前值：" + port);
        }
    }
    
    return Optional.empty();
}
```

---

### ConfigLoader.java - 错误处理

```java
private static Config loadFromFile(String path) throws IOException {
    try {
        String content = Files.readString(configFile.toPath());
        Config config = objectMapper.readValue(content, Config.class);
        
        // 确保必要字段不为 null
        if (config.getAgent() == null) config.setAgent(new AgentConfig());
        if (config.getProviders() == null) config.setProviders(new ProvidersConfig());
        if (config.getTools() == null) config.setTools(new ToolsConfig());
        if (config.getGateway() == null) config.setGateway(new GatewayConfig());
        
        return config;
    } catch (Exception e) {
        // 显示详细错误信息和建议
        System.err.println("⚠️  配置文件加载失败：" + e.getMessage());
        System.err.println("可能原因：...");
        System.err.println("建议：...");
        
        // 使用默认配置继续
        return Config.defaultConfig();
    }
}
```

---

### GatewayCommand.java - 启动前验证

```java
private AgentContext createAgentContext(Config config) {
    // 首先验证配置
    var validationError = config.validate();
    if (validationError.isPresent()) {
        System.err.println("⚠️  配置验证警告：" + validationError.get());
        System.err.println("建议修复配置：...");
    }
    
    // 即使有错误也继续创建（降级模式）
    try {
        AgentLoop agentLoop = getAgentLoop();
        boolean providerConfigured = (agentLoop != null);
        
        if (!providerConfigured) {
            System.out.println("⚠️  LLM Provider 未配置，但仍可启动 Web Console 进行配置");
        }
        
        return new AgentContext(agentLoop, bus, providerConfigured);
    } catch (Exception e) {
        System.err.println("⚠️  AgentLoop 创建失败：" + e.getMessage());
        return new AgentContext(null, new MessageBus(), false);
    }
}
```

---

## 测试验证

### 测试用例

| 场景 | 预期结果 | 实际结果 |
|------|----------|----------|
| 无配置文件 | 提示 onboard | ✅ PASS |
| JSON 格式错误 | 显示解析错误 + 建议 | ✅ PASS |
| API Key 为空 | 显示警告 + 降级启动 | ✅ PASS |
| API Key 格式错误 | 显示格式警告 | ✅ PASS |
| Provider 缺失 | 显示配置错误 | ✅ PASS |
| 工作空间为空 | 显示字段错误 | ✅ PASS |
| 端口无效 | 显示范围错误 | ✅ PASS |

---

## 最佳实践

### 1. 首次部署

```bash
# 1. 生成配置
java -jar jobclaw.jar onboard

# 2. 编辑配置
nano ~/.jobclaw/config.json

# 3. 验证配置
cat ~/.jobclaw/config.json | jq .

# 4. 启动服务
java -jar jobclaw.jar gateway

# 5. 检查日志
tail -f /tmp/jobclaw_run.log
```

---

### 2. 配置更新

```bash
# 1. 备份旧配置
cp ~/.jobclaw/config.json ~/.jobclaw/config.json.backup

# 2. 编辑配置
nano ~/.jobclaw/config.json

# 3. 验证 JSON 格式
python3 -m json.tool ~/.jobclaw/config.json > /dev/null && echo "JSON 格式正确"

# 4. 重启服务
pkill -f jobclaw
java -jar jobclaw.jar gateway
```

---

### 3. 故障排查

```bash
# 1. 查看配置文件
cat ~/.jobclaw/config.json

# 2. 验证 JSON 格式
https://jsonlint.com/

# 3. 检查日志
tail -100 /tmp/jobclaw_run.log

# 4. 测试连接
curl http://127.0.0.1:18791/api/status

# 5. 重新生成配置
java -jar jobclaw.jar onboard
```

---

## 总结

JobClaw 现在具备：

✅ **完善的配置验证** - 检查所有关键字段  
✅ **友好的错误提示** - 清晰的中文错误信息  
✅ **可操作的建议** - 告诉用户如何修复  
✅ **降级运行能力** - 配置错误也能启动  
✅ **详细的日志** - 便于故障排查  

**目标：** 让用户不再害怕配置错误，每个错误都有明确的解决路径！

---

**Commit:** `66acb97`  
**GitHub:** `github.com:liushenling2001/JobClaw.git`
