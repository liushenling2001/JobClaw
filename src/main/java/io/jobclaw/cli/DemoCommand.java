package io.jobclaw.cli;

import io.jobclaw.agent.AgentLoop;
import io.jobclaw.agent.AgentOrchestrator;
import io.jobclaw.config.Config;
import io.jobclaw.config.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demo 命令 - 一键运行可复现的演示流程
 *
 * 当前支持的子模式：
 * - agent-basic：构造一个固定问题，直接通过 AgentLoop 跑完一轮 CLI 对话链路，方便现场演示。
 *
 * 学习/演示提示：
 * 结合 README 中的"5 分钟 Demo / Demo 1"，可以先执行 jobclaw demo agent-basic，
 * 再对照 JobClaw → DemoCommand → AgentLoop 的调用关系，向听众讲解从配置加载、
 * LLM Provider 初始化，到一次完整推理流程的关键步骤。
 */
public class DemoCommand extends CliCommand {

    private static final Logger logger = LoggerFactory.getLogger(DemoCommand.class);

    @Override
    public String name() {
        return "demo";
    }

    @Override
    public String description() {
        return "运行内置 Demo 流程（如 agent-basic, agent-multi）";
    }

    @Override
    public int execute(String[] args) throws Exception {
        if (args.length == 0) {
            printHelp();
            return 1;
        }

        String mode = args[0];
        if ("agent-basic".equals(mode)) {
            return runAgentBasicDemo();
        } else if ("agent-multi".equals(mode)) {
            return runMultiAgentDemo();
        } else {
            System.out.println("未知 Demo 模式：" + mode);
            printHelp();
            return 1;
        }
    }

    private int runMultiAgentDemo() {
        System.out.println(LOGO + " Running demo: agent-multi (Multi-Agent Collaboration)");
        System.out.println("这个 Demo 会展示多智能体协作完成复杂任务的流程。\n");

        // 1. 加载配置
        Config config;
        try {
            config = ConfigLoader.load(getConfigPath());
            System.out.println("配置加载成功，使用模型：" + config.getAgent().getModel());
        } catch (Exception e) {
            System.err.println("Error loading config: " + e.getMessage());
            System.err.println("请先运行 'jobclaw onboard' 完成初始化，并配置好 API Key。");
            return 1;
        }

        // 2. 获取编排器
        AgentOrchestrator orchestrator = getApplicationContext()
            .getBean(AgentOrchestrator.class);
        if (orchestrator == null) {
            System.err.println("Error: AgentOrchestrator bean not found");
            return 1;
        }

        // 3. 构造多 Agent 任务
        String sessionKey = "demo:agent-multi";
        String task = "请用多智能体协作模式完成以下任务：\n" +
            "开发一个简单的 Java 工具类，用于读取和写入 JSON 文件。\n" +
            "要求：\n" +
            "1. 支持读取 JSON 文件并解析为 Map\n" +
            "2. 支持将 Map 写入 JSON 文件\n" +
            "3. 包含错误处理\n" +
            "4. 提供使用示例";

        System.out.println("示例任务：" + task + "\n");
        System.out.println("正在启动多智能体协作...\n");
        
        try {
            long startTime = System.currentTimeMillis();
            String result = orchestrator.process(sessionKey, task);
            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println(LOGO + " 多智能体协作完成 (耗时：" + elapsed + "ms)\n");
            System.out.println("=== 响应开始 ===");
            System.out.println(result);
            System.out.println("=== 响应结束 ===\n");

            System.out.println(LOGO + " Demo 完成！");
            return 0;

        } catch (Exception e) {
            System.err.println("Error running multi-agent demo: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private int runAgentBasicDemo() {
        System.out.println(LOGO + " Running demo: agent-basic");
        System.out.println("这个 Demo 会加载配置、初始化 LLMProvider 和 AgentLoop，然后用一个固定问题跑完一次 CLI 对话流程。\n");

        // 1. 加载配置（用于验证配置是否正确加载）
        Config config;
        try {
            config = ConfigLoader.load(getConfigPath());
            System.out.println("配置加载成功，使用模型：" + config.getAgent().getModel());
        } catch (Exception e) {
            System.err.println("Error loading config: " + e.getMessage());
            System.err.println("请先运行 'jobclaw onboard' 完成初始化，并配置好 API Key。");
            return 1;
        }

        // 2. 从 Spring 获取 AgentLoop（已经由 Spring 初始化好）
        AgentLoop agentLoop = getAgentLoop();
        if (agentLoop == null) {
            System.err.println("Error: AgentLoop bean not found in Spring context");
            return 1;
        }

        // 3. 构造固定问题并调用 processDirect
        String sessionKey = "demo:agent-basic";
        String question = "请用简短的中文（3-5 句话）介绍一下什么是 AI Agent，以及它如何帮助用户完成任务。";

        System.out.println("示例问题：" + question + "\n");
        try {
            String answer = agentLoop.process(sessionKey, question);
            System.out.println(LOGO + " Demo 响应:\n");
            System.out.println(answer);
            System.out.println();
            System.out.println("（可以打开 AgentLoop、MessageBus、ToolRegistry 等类，对照这次 Demo 的日志来讲解内部流程。）");
        } catch (Exception e) {
            System.err.println("运行 Demo 时出错：" + e.getMessage());
            e.printStackTrace();
            return 1;
        }

        return 0;
    }

    @Override
    public void printHelp() {
        System.out.println(LOGO + " jobclaw demo - 运行内置演示流程");
        System.out.println();
        System.out.println("Usage: jobclaw demo <mode>");
        System.out.println();
        System.out.println("可用模式:");
        System.out.println("  agent-basic    加载配置并跑一轮固定问题的 CLI 对话演示");
        System.out.println("  agent-multi    多智能体协作演示（复杂任务）");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  jobclaw demo agent-basic");
        System.out.println("  jobclaw demo agent-multi");
    }

    private org.springframework.context.ApplicationContext getApplicationContext() {
        try {
            java.lang.reflect.Method getMethod = CliCommand.class.getDeclaredMethod("getAppContext");
            getMethod.setAccessible(true);
            return (org.springframework.context.ApplicationContext) getMethod.invoke(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get ApplicationContext", e);
        }
    }
}
