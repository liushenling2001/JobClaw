package io.jobclaw.cli;

import io.jobclaw.agent.AgentLoop;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.config.Config;
import io.jobclaw.providers.LLMProvider;
import io.jobclaw.session.SessionManager;
import io.jobclaw.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 网关命令，启动 JobClaw 网关服务器。
 *
 * 核心功能：
 * - 启动完整的网关服务（通道、定时任务、心跳、Webhook、Web Console）
 * - 支持无 LLM Provider 启动，可通过 Web Console 后续配置
 * - 提供多通道消息接入（钉钉、飞书、QQ、Telegram、Discord 等）
 * - 内置 Web Console 管理界面
 *
 * 服务架构：
 * - MessageBus：消息总线，协调各组件通信
 * - AgentLoop：Agent 主循环，处理用户消息
 * - ChannelManager：管理所有通道的生命周期
 * - CronService：定时任务调度
 * - HeartbeatService：心跳检测
 *
 * 使用场景：
 * - 生产环境部署，提供 24/7 服务
 * - 多通道接入，统一管理多个 IM 平台
 * - 团队协作，共享 Agent 服务
 */
public class GatewayCommand extends CliCommand {

    private static final Logger logger = LoggerFactory.getLogger(GatewayCommand.class);

    private static final String WARNING_NO_PROVIDER = "⚠️  LLM Provider 未配置，但仍可启动 Web Console 进行配置";
    private static final String GUIDE_WEB_CONSOLE = "👉 请访问 Web Console 配置 LLM Provider:";
    private static final String SHUTDOWN_TIP = "按 Ctrl+C 停止";

    @Override
    public String name() {
        return "gateway";
    }

    @Override
    public String description() {
        return "启动 jobclaw 网关";
    }

    @Override
    public int execute(String[] args) throws Exception {
        // 解析命令行参数
        boolean debug = parseDebugFlag(args);

        // 加载配置并创建 Agent
        Config config = loadConfig();
        if (config == null) {
            return 1;
        }

        AgentContext agentContext = createAgentContext(config);

        // 创建并启动网关
        GatewayBootstrap gateway = createAndStartGateway(config, agentContext);

        // 打印启动信息
        printStartupInfo(gateway, config, agentContext.providerConfigured);

        // 等待关闭
        gateway.awaitShutdown();

        return 0;
    }

    /**
     * 解析调试标志。
     *
     * @param args 命令行参数
     * @return 是否启用调试模式
     */
    private boolean parseDebugFlag(String[] args) {
        for (String arg : args) {
            if ("--debug".equals(arg) || "-d".equals(arg)) {
                System.out.println("🔍 调试模式已启用");
                return true;
            }
        }
        return false;
    }

    /**
     * 创建 Agent 上下文。
     *
     * @param config 配置对象
     * @return Agent 上下文
     */
    private AgentContext createAgentContext(Config config) {
        // 首先验证配置
        if (config != null) {
            var validationError = config.validate();
            if (validationError.isPresent()) {
                System.err.println();
                System.err.println("⚠️  配置验证警告：" + validationError.get());
                System.err.println();
                System.err.println("服务仍可启动，但部分功能可能无法使用。");
                System.err.println();
                System.err.println("建议修复配置：");
                System.err.println("  1. 编辑配置文件：nano ~/.jobclaw/config.json");
                System.err.println("  2. 参考配置示例：https://github.com/liushenling2001/JobClaw#%E9%85%8D%E7%BD%AE");
                System.err.println("  3. 重新生成配置：jobclaw onboard");
                System.err.println();
            }
        }

        try {
            // 从 Spring 获取 AgentLoop
            AgentLoop agentLoop = getAgentLoop();
            boolean providerConfigured = (agentLoop != null);

            if (!providerConfigured) {
                System.out.println();
                System.out.println(WARNING_NO_PROVIDER);
                System.out.println();
                System.out.println("配置问题：");
                if (config != null && config.getAgent() != null) {
                    System.out.println("  • Provider: " + config.getAgent().getProvider());
                    System.out.println("  • Model: " + config.getAgent().getModel());
                }
                System.out.println();
            }

            // 创建消息总线
            MessageBus bus = new MessageBus();

            // 打印 Agent 状态
            if (providerConfigured) {
                System.out.println(LOGO + " Agent 已初始化");
                System.out.println("  • 模型：" + config.getAgent().getModel());
                System.out.println("  • Provider: " + config.getAgent().getProvider());
            }

            return new AgentContext(agentLoop, bus, providerConfigured);
        } catch (Exception e) {
            System.err.println();
            System.err.println("⚠️  AgentLoop 创建失败：" + e.getMessage());
            System.err.println();
            System.err.println("服务将以受限模式启动（无 LLM 能力）");
            System.err.println();
            e.printStackTrace(System.err);
            return new AgentContext(null, new MessageBus(), false);
        }
    }

    /**
     * 创建并启动网关。
     *
     * @param config 配置对象
     * @param agentContext Agent 上下文
     * @return 网关实例
     */
    private GatewayBootstrap createAndStartGateway(Config config, AgentContext agentContext) {
        return new GatewayBootstrap(config, agentContext.agentLoop, agentContext.bus)
                .initialize()
                .start();
    }

    /**
     * 打印网关启动信息。
     *
     * @param gateway 网关实例
     * @param config 配置对象
     * @param providerConfigured Provider 是否已配置
     */
    private void printStartupInfo(GatewayBootstrap gateway, Config config, boolean providerConfigured) {
        // 打印通道信息
        printChannelInfo(gateway);

        // 打印网关基本信息
        printGatewayBasicInfo(config);

        // 打印服务状态
        printServiceStatus();

        // 打印 Web Console 信息
        printWebConsoleInfo(gateway, providerConfigured);
    }

    /**
     * 打印通道信息。
     *
     * @param gateway 网关实例
     */
    private void printChannelInfo(GatewayBootstrap gateway) {
        List<String> enabledChannels = gateway.getEnabledChannels();
        if (!enabledChannels.isEmpty()) {
            System.out.println("✓ 已启用通道：" + String.join(", ", enabledChannels));
        } else {
            System.out.println("⚠ 警告：没有启用任何通道");
        }
    }

    /**
     * 打印网关基本信息。
     *
     * @param config 配置对象
     */
    private void printGatewayBasicInfo(Config config) {
        System.out.println("✓ 网关已启动于 " + config.getGateway().getHost() + ":" + config.getGateway().getPort());
        System.out.println(SHUTDOWN_TIP);
    }

    /**
     * 打印服务状态。
     */
    private void printServiceStatus() {
        System.out.println("✓ 定时任务服务已启动");
        System.out.println("✓ 心跳服务已启动");
        System.out.println("✓ 通道服务已启动");
    }

    /**
     * 打印 Web Console 信息。
     *
     * @param gateway 网关实例
     * @param providerConfigured Provider 是否已配置
     */
    private void printWebConsoleInfo(GatewayBootstrap gateway, boolean providerConfigured) {
        System.out.println("✓ Web Console 已启动");
        System.out.println("  • 访问地址：" + gateway.getWebConsoleUrl());

        // 如果 Provider 未配置，提示用户通过 Web Console 配置
        if (!providerConfigured) {
            System.out.println();
            System.out.println(GUIDE_WEB_CONSOLE);
            System.out.println("   " + gateway.getWebConsoleUrl() + " -> Settings -> Models");
        }
    }

    @Override
    public void printHelp() {
        System.out.println(LOGO + " jobclaw gateway - 启动网关服务器");
        System.out.println();
        System.out.println("Usage: jobclaw gateway [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -d, --debug    启用调试模式");
    }

    /**
     * Agent 上下文封装类。
     */
    private record AgentContext(AgentLoop agentLoop, MessageBus bus, boolean providerConfigured) {
    }
}
