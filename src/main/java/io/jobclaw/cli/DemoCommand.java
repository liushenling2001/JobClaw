package io.jobclaw.cli;

import io.jobclaw.agent.AgentLoop;
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
        return "运行内置 Demo 流程（如 agent-basic）";
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
        } else {
            System.out.println("未知 Demo 模式：" + mode);
            printHelp();
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
        String question = "请用中文介绍一下 jobclaw 的架构，并简要说明一次消息是如何从命令行流经 Agent 再返回给用户的。";

        System.out.println("示例问题：" + question + "\n");
        try {
            String answer = agentLoop.processDirect(sessionKey, question);
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
        System.out.println();
        System.out.println("示例:");
        System.out.println("  jobclaw demo agent-basic");
    }
}
