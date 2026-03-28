package io.jobclaw.cli;

import io.jobclaw.config.Config;
import io.jobclaw.config.ConfigLoader;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * 初始化命令，初始化 JobClaw 配置和工作空间。
 *
 * 核心功能：
 * - 创建默认配置文件（~/.jobclaw/config.json）
 * - 创建工作空间目录结构（workspace、memory、skills、sessions、cron）
 * - 生成模板文件（AGENTS.md、SOUL.md、USER.md、IDENTITY.md、PROFILE.md、MEMORY.md、HEARTBEAT.md）
 * - 提供下一步操作指引
 *
 * 工作空间结构：
 * - workspace/：主工作目录
 *   - memory/：长期记忆和每日笔记
 *     - MEMORY.md：长期记忆文件
 *     - HEARTBEAT.md：心跳上下文文件
 *   - skills/：技能目录
 *   - sessions/：会话历史
 *   - cron/：定时任务配置
 *   - AGENTS.md：Agent 行为指令
 *   - SOUL.md：Agent 个性定义
 *   - USER.md：用户信息模板
 *   - IDENTITY.md：Agent 身份信息
 *   - PROFILE.md：配置信息
 *
 * 使用场景：
 * - 首次安装 JobClaw
 * - 重置配置和工作空间
 * - 创建新的工作环境
 */
@Component
public class OnboardCommand extends CliCommand {

    private static final String CONFIRM_YES = "y";                    // 确认覆盖的输入
    private static final String ABORT_MESSAGE = "已中止。";            // 中止消息
    private static final String READY_MESSAGE = " JobClaw 已就绪！";   // 就绪消息

    private static final String DIR_MEMORY = "memory";      // 记忆目录
    private static final String DIR_SKILLS = "skills";      // 技能目录
    private static final String DIR_SESSIONS = "sessions";  // 会话目录
    private static final String DIR_CRON = "cron";          // 定时任务目录

    private static final String FILE_AGENTS = "AGENTS.md";     // Agent 指令文件
    private static final String FILE_SOUL = "SOUL.md";         // Agent 灵魂文件
    private static final String FILE_USER = "USER.md";         // 用户信息文件
    private static final String FILE_IDENTITY = "IDENTITY.md"; // 身份信息文件
    private static final String FILE_PROFILE = "PROFILE.md";   // 配置文件
    private static final String FILE_MEMORY = "MEMORY.md";     // 记忆文件
    private static final String FILE_HEARTBEAT = "HEARTBEAT.md"; // 心跳上下文文件

    @Override
    public String name() {
        return "onboard";
    }

    @Override
    public String description() {
        return "初始化 jobclaw 配置和工作空间";
    }

    @Override
    public int execute(String[] args) throws Exception {
        String configPath = getConfigPath();

        // 检查配置是否存在并确认覆盖
        if (!confirmOverwriteIfExists(configPath)) {
            return 0;
        }

        // 创建并保存默认配置
        Config config = createAndSaveConfig(configPath);

        // 创建工作空间目录结构
        createWorkspaceDirectories(config.getWorkspacePath());

        // 创建工作空间模板文件
        createWorkspaceTemplates(config.getWorkspacePath());

        // 打印完成信息和下一步指引
        printCompletionMessage(configPath);

        return 0;
    }

    /**
     * 确认覆盖已存在的配置。
     *
     * @param configPath 配置文件路径
     * @return 如果可以继续返回 true，否则返回 false
     */
    private boolean confirmOverwriteIfExists(String configPath) {
        File configFile = new File(configPath);
        if (!configFile.exists()) {
            return true;
        }

        System.out.println("配置已存在于 " + configPath);
        System.out.print("覆盖？[y/N] ");

        Scanner scanner = new Scanner(System.in);
        String response = scanner.nextLine().trim().toLowerCase();

        if (!CONFIRM_YES.equals(response)) {
            System.out.println(ABORT_MESSAGE);
            return false;
        }

        return true;
    }

    /**
     * 创建并保存默认配置。
     *
     * @param configPath 配置文件路径
     * @return 配置对象
     */
    private Config createAndSaveConfig(String configPath) throws IOException {
        Config config = Config.defaultConfig();

        // 确保父目录存在
        File configFile = new File(configPath);
        configFile.getParentFile().mkdirs();

        // 保存配置
        ConfigLoader.save(configPath, config);

        System.out.println();
        System.out.println("  已创建配置文件：" + configPath);

        return config;
    }

    /**
     * 创建工作空间目录结构。
     *
     * @param workspace 工作空间路径
     */
    private void createWorkspaceDirectories(String workspace) {
        System.out.println();
        System.out.println("创建工作空间目录:");

        createDirectory(workspace);
        createDirectory(workspace + "/" + DIR_MEMORY);
        createDirectory(workspace + "/" + DIR_SKILLS);
        createDirectory(workspace + "/" + DIR_SESSIONS);
        createDirectory(workspace + "/" + DIR_CRON);
    }

    /**
     * 打印完成信息和下一步指引。
     *
     * @param configPath 配置文件路径
     */
    private void printCompletionMessage(String configPath) {
        System.out.println();
        System.out.println(LOGO + READY_MESSAGE);
        System.out.println();
        System.out.println("下一步：");
        System.out.println();
        System.out.println("1️⃣ 配置 LLM Provider（必需）");
        System.out.println("   编辑 " + configPath + "，添加你的 API Key：");
        System.out.println("   {");
        System.out.println("     \"providers\": {");
        System.out.println("       \"dashscope\": {");
        System.out.println("         \"baseUrl\": \"https://dashscope.aliyuncs.com/compatible-mode/v1\",");
        System.out.println("         \"apiKey\": \"sk-xxx\"  // 通义千问 API Key");
        System.out.println("       }");
        System.out.println("     }");
        System.out.println("   }");
        System.out.println();
        System.out.println("2️⃣ 配置搜索工具（可选，推荐）");
        System.out.println("   百度千帆搜索 API Key：");
        System.out.println("   {");
        System.out.println("     \"tools\": {");
        System.out.println("       \"web\": {");
        System.out.println("         \"search\": {");
        System.out.println("           \"api_key\": \"你的千帆 API Key\"  // 百度千帆 API Key");
        System.out.println("         }");
        System.out.println("       }");
        System.out.println("     }");
        System.out.println("   }");
        System.out.println();
        System.out.println("3️⃣ 启动服务");
        System.out.println("   java -jar jobclaw.jar gateway  # 启动网关服务");
        System.out.println("   java -jar jobclaw.jar agent    # CLI 交互模式");
        System.out.println();
        System.out.println("📖 获取 API Key：");
        System.out.println("   • 通义千问：https://dashscope.console.aliyun.com/apiKey");
        System.out.println("   • 百度千帆：https://cloud.baidu.com/product/qianfan");
        System.out.println();
    }

    /**
     * 创建目录（如果不存在）。
     *
     * @param path 目录路径
     */
    private void createDirectory(String path) {
        File dir = new File(path);
        if (!dir.exists()) {
            dir.mkdirs();
            System.out.println("  已创建目录：" + path);
        }
    }

    /**
     * 创建工作空间模板文件。
     *
     * @param workspace 工作空间路径
     */
    private void createWorkspaceTemplates(String workspace) {
        System.out.println();
        System.out.println("创建模板文件:");

        Map<String, String> templates = buildTemplateMap();

        // 创建模板文件
        for (Map.Entry<String, String> entry : templates.entrySet()) {
            createTemplateFile(workspace, entry.getKey(), entry.getValue());
        }

        // 创建记忆文件
        createMemoryFile(workspace);

        // 创建心跳上下文文件
        createHeartbeatFile(workspace);
    }

    /**
     * 构建模板映射。
     *
     * @return 文件名到内容的映射
     */
    private Map<String, String> buildTemplateMap() {
        Map<String, String> templates = new HashMap<>();

        templates.put(FILE_AGENTS, buildAgentsTemplate());
        templates.put(FILE_SOUL, buildSoulTemplate());
        templates.put(FILE_USER, buildUserTemplate());
        templates.put(FILE_IDENTITY, buildIdentityTemplate());
        templates.put(FILE_PROFILE, buildProfileTemplate());

        return templates;
    }

    /**
     * 构建 AGENTS.md 模板内容。
     *
     * @return 模板内容
     */
    private String buildAgentsTemplate() {
        return "# Agent 指令\n\n" +
                "你是一个有用的 AI 助手。要简洁、准确和友好。\n\n" +
                "## 指导原则\n\n" +
                "- 在采取行动之前始终解释你在做什么\n" +
                "- 当请求不明确时要求澄清\n" +
                "- 使用工具来帮助完成任务\n" +
                "- 在你的记忆文件中记住重要信息\n" +
                "- 要积极主动和乐于助人\n" +
                "- 从用户反馈中学习\n\n" +
                "## 多智能体协作\n\n" +
                "你是主 Agent，负责判断何时需要协作并管理子 Agent。\n\n" +
                "### 何时使用多 Agent 协作\n\n" +
                "**适用场景：**\n" +
                "- 任务复杂，需要多个专业角色协作（如开发 + 审查）\n" +
                "- 需要独立验证结果或从不同角度分析\n" +
                "- 涉及多个独立子任务，可以并行处理\n" +
                "- 用户明确要求多 Agent 或指定角色\n\n" +
                "**不需要协作的情况：**\n" +
                "- 简单问题、概念解释、信息查询\n" +
                "- 单一步骤即可完成的任务\n" +
                "- 用户只是询问能力（如\"你支持多智能体吗\"）\n\n" +
                "### 使用 spawn 工具\n\n" +
                "```java\n" +
                "// 指定角色\n" +
                "spawn(task=\"开发一个用户管理系统\", role=\"coder\")\n" +
                "spawn(task=\"研究最新的前端框架\", role=\"researcher\")\n" +
                "spawn(task=\"审查代码质量\", role=\"reviewer\")\n" +
                "\n" +
                "// 不指定角色，让系统自动分配\n" +
                "spawn(task=\"完成这个复杂的数据分析任务\")\n" +
                "\n" +
                "// 异步执行（后台运行）\n" +
                "spawn(task=\"生成测试报告\", async=true)\n" +
                "```\n\n" +
                "### 可用角色\n" +
                "- **Assistant**：通用助手\n" +
                "- **Coder**：编程专家\n" +
                "- **Researcher**：信息收集\n" +
                "- **Writer**：内容创作\n" +
                "- **Reviewer**：质量审查\n" +
                "- **Planner**：任务规划\n" +
                "- **Tester**：测试验证\n\n" +
                "### 最佳实践\n" +
                "1. 先分析任务复杂度，再决定是否协作\n" +
                "2. 给子 Agent 清晰、具体的任务描述\n" +
                "3. 汇总子 Agent 结果时，提供完整的上下文\n" +
                "4. 避免过度协作（简单任务不需要 spawn）\n\n" +
                "### 使用示例\n\n" +
                "当遇到复杂任务时，使用 `spawn` 工具创建子 Agent：\n\n" +
                "**适用场景：**\n" +
                "- 任务需要多个专业角色（Coder + Reviewer）\n" +
                "- 需要独立验证结果\n" +
                "- 并行处理多个子任务\n\n" +
                "**示例：**\n" +
                "- \"这个功能比较复杂，我需要请一位 Coder 和一位 Reviewer 协作完成\"\n" +
                "- \"让我先请 Researcher 收集相关信息\"\n\n" +
                "## 工具使用规范\n\n" +
                "- 搜索信息用 `web_search`（百度千帆）\n" +
                "- 读取文档用 `read_word` / `read_excel`\n" +
                "- 文件操作用 `read_file` / `write_file` / `edit_file`\n" +
                "- 执行命令用 `exec`（注意安全）\n" +
                "- 定时任务用 `cron`\n" +
                "- 发送消息用 `message`\n" +
                "- 创建子 Agent 用 `spawn`\n" +
                "- 查询用量用 `query_token_usage`\n" +
                "- 连接 MCP 服务器用 `mcp`\n";
    }

    /**
     * 构建 SOUL.md 模板内容。
     *
     * @return 模板内容
     */
    private String buildSoulTemplate() {
        return "# 灵魂\n\n" +
                "我是 JobClaw，一个由 AI 驱动的轻量级 AI 助手。\n\n" +
                "## 个性\n\n" +
                "- 乐于助人和友好\n" +
                "- 简洁扼要\n" +
                "- 好奇且渴望学习\n" +
                "- 诚实和透明\n\n" +
                "## 价值观\n\n" +
                "- 准确性优于速度\n" +
                "- 用户隐私和安全\n" +
                "- 行动透明\n" +
                "- 持续改进\n";
    }

    /**
     * 构建 USER.md 模板内容。
     *
     * @return 模板内容
     */
    private String buildUserTemplate() {
        return "# 用户\n\n" +
                "此处填写用户信息。\n\n" +
                "## 偏好\n\n" +
                "- 沟通风格：（随意/正式）\n" +
                "- 时区：（你的时区）\n" +
                "- 语言：（你的首选语言）\n\n" +
                "## 个人信息\n\n" +
                "- 姓名：（可选）\n" +
                "- 位置：（可选）\n" +
                "- 职业：（可选）\n\n" +
                "## 学习目标\n\n" +
                "- 用户希望从 AI 学到什么\n" +
                "- 首选的交互风格\n" +
                "- 兴趣领域\n";
    }

    /**
     * 构建 IDENTITY.md 模板内容。
     *
     * @return 模板内容
     */
    private String buildIdentityTemplate() {
        return "# 身份\n\n" +
                "## 名称\n" +
                "JobClaw 👨‍🔧\n\n" +
                "## 描述\n" +
                "用 Java 编写的轻量级 AI Agent 框架（基于 Spring Boot 3.3 + Spring AI 1.0）。\n\n" +
                "## 版本\n" +
                "1.0.0\n\n" +
                "## 目的\n" +
                "- 提供强大的 AI Agent 能力\n" +
                "- 支持多 LLM 提供商（通义千问、OpenAI、智谱等）\n" +
                "- 支持多智能体协作\n" +
                "- 支持 MCP（Model Context Protocol）扩展\n\n" +
                "## 核心能力\n\n" +
                "### 🛠️ 工具系统（14 个内置工具）\n" +
                "- **文件操作**：read_file, write_file, list_dir, edit_file, append_file\n" +
                "- **文档处理**：read_word (.doc/.docx), read_excel (.xls/.xlsx)\n" +
                "- **命令执行**：exec（跨平台，安全控制）\n" +
                "- **网络工具**：web_search（百度千帆）, web_fetch（网页抓取）\n" +
                "- **系统工具**：cron（定时任务）, message（消息发送）, spawn（子 Agent）, query_token_usage（用量统计）\n" +
                "- **MCP 集成**：mcp（Model Context Protocol，支持外部服务器）\n" +
                "\n" +
                "### 🤖 多智能体协作\n" +
                "- **7 个预设角色**：Assistant, Coder, Researcher, Writer, Reviewer, Planner, Tester\n" +
                "- **4 步协作流程**：Planner 分析 → Executors 执行 → Reviewer 审查 → Writer 汇总\n" +
                "- **自动检测**：根据关键词自动触发多 Agent 模式\n" +
                "- **递归保护**：防止子 Agent 无限循环\n" +
                "\n" +
                "### 📡 通道集成（7 个平台）\n" +
                "- 飞书、WhatsApp、Telegram、Discord、QQ、钉钉、MaixCam\n" +
                "- 统一 MessageBus 消息总线\n" +
                "- 支持官方 SDK（如飞书 lark-oapi-sdk）\n" +
                "\n" +
                "### 🧠 记忆系统\n" +
                "- 长期记忆（MEMORY.md）\n" +
                "- 每日笔记（memory/YYYY-MM-DD.md）\n" +
                "- 心跳检查（HEARTBEAT.md）\n" +
                "- Token 用量追踪\n" +
                "\n" +
                "### 🔌 扩展能力\n" +
                "- MCP（Model Context Protocol）支持外部工具服务器\n" +
                "- 技能系统（Skills）\n" +
                "- 定时任务（Cron）\n" +
                "- 自定义 Agent 角色\n";
    }

    /**
     * 构建 PROFILE.md 模板内容。
     *
     * @return 模板内容
     */
    private String buildProfileTemplate() {
        return "# 配置文件\n\n" +
                "此文件包含 Agent 的运行配置和状态信息。\n\n" +
                "## 系统信息\n\n" +
                "- 启动时间：（首次启动时记录）\n" +
                "- 工作空间：~/.jobclaw/workspace/\n" +
                "- 配置文件：~/.jobclaw/config.json\n\n" +
                "## 运行统计\n\n" +
                "- 总会话数：0\n" +
                "- 总任务数：0\n" +
                "- 最后活跃时间：（自动更新）\n\n" +
                "## 状态\n\n" +
                "- 健康状态：正常\n" +
                "- 活跃通道：（记录已连接的通道）\n" +
                "- 已加载技能：（记录已加载的技能列表）\n";
    }

    /**
     * 创建模板文件。
     *
     * @param workspace 工作空间路径
     * @param filename 文件名
     * @param content 文件内容
     */
    private void createTemplateFile(String workspace, String filename, String content) {
        Path filePath = Paths.get(workspace, filename);

        if (Files.exists(filePath)) {
            return;
        }

        try {
            Files.writeString(filePath, content);
            System.out.println("  已创建 " + filename);
        } catch (IOException e) {
            System.err.println("  创建文件失败 " + filename + ": " + e.getMessage());
        }
    }

    /**
     * 创建记忆文件。
     *
     * @param workspace 工作空间路径
     */
    private void createMemoryFile(String workspace) {
        Path memoryFile = Paths.get(workspace, DIR_MEMORY, FILE_MEMORY);

        if (Files.exists(memoryFile)) {
            return;
        }

        String memoryContent = buildMemoryTemplate();

        try {
            Files.writeString(memoryFile, memoryContent);
            System.out.println("  已创建 " + DIR_MEMORY + "/" + FILE_MEMORY);
        } catch (IOException e) {
            System.err.println("  创建内存文件失败：" + e.getMessage());
        }
    }

    /**
     * 构建 MEMORY.md 模板内容。
     *
     * @return 模板内容
     */
    private String buildMemoryTemplate() {
        return "# 长期记忆\n\n" +
                "此文件存储应该在各会话之间持久化的重要信息。\n\n" +
                "## 用户信息\n\n" +
                "（关于用户的重要事实）\n\n" +
                "## 偏好\n\n" +
                "（随时间学习到的用户偏好）\n\n" +
                "## 重要笔记\n\n" +
                "（需要记住的事情）\n";
    }

    /**
     * 构建 HEARTBEAT.md 模板内容。
     *
     * @return 模板内容
     */
    private String buildHeartbeatTemplate() {
        return "# 心跳检查\n\n" +
                "此文件定义心跳服务的检查内容。保持简洁以降低 token 消耗。\n\n" +
                "## 日常检查\n\n" +
                "- 确保今日日志 memory/YYYY-MM-DD.md 存在\n" +
                "- 检查定时任务执行状态\n\n" +
                "## 主动行为\n\n" +
                "- 如果发现系统异常，主动报告\n" +
                "- 如果有未完成的重要任务，继续推进\n\n" +
                "## 注意事项\n\n" +
                "- 心跳内容会在每个心跳周期被读取\n" +
                "- 保持内容简洁，避免过多 token 消耗\n" +
                "- 定期任务应使用 cron 而非心跳\n";
    }

    /**
     * 创建心跳上下文文件。
     *
     * @param workspace 工作空间路径
     */
    private void createHeartbeatFile(String workspace) {
        Path heartbeatFile = Paths.get(workspace, DIR_MEMORY, FILE_HEARTBEAT);

        if (Files.exists(heartbeatFile)) {
            return;
        }

        String heartbeatContent = buildHeartbeatTemplate();

        try {
            Files.writeString(heartbeatFile, heartbeatContent);
            System.out.println("  已创建 " + DIR_MEMORY + "/" + FILE_HEARTBEAT);
        } catch (IOException e) {
            System.err.println("  创建心跳文件失败：" + e.getMessage());
        }
    }

    @Override
    public void printHelp() {
        System.out.println(LOGO + " jobclaw onboard - 初始化配置");
        System.out.println();
        System.out.println("Usage: jobclaw onboard");
        System.out.println();
        System.out.println("此命令将：");
        System.out.println("  - 在 ~/.jobclaw/config.json 创建默认配置");
        System.out.println("  - 在 ~/.jobclaw/workspace 创建工作空间目录");
        System.out.println("  - 创建模板文件（AGENTS.md, SOUL.md, USER.md, IDENTITY.md, PROFILE.md 等）");
        System.out.println("  - 在 memory/ 目录创建 MEMORY.md 和 HEARTBEAT.md");
    }
}
