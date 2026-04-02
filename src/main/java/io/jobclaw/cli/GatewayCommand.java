package io.jobclaw.cli;

import io.jobclaw.config.Config;
import io.jobclaw.gateway.GatewayService;
import io.jobclaw.SpringContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 网关命令，启动 JobClaw 网关服务器。
 */
@Component
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

        // 加载配置
        Config config = loadConfig();
        if (config == null) {
            return 1;
        }

        // 从 Spring 获取 GatewayService 并启动
        GatewayService gateway = SpringContext.getBean(GatewayService.class);
        if (gateway == null) {
            System.err.println("GatewayService 不可用");
            return 1;
        }

        // 打印启动信息
        printStartupInfo(gateway, config);

        // 启动网关
        gateway.start();

        // 等待关闭
        gateway.awaitShutdown();

        return 0;
    }

    /**
     * 解析调试标志。
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
     * 打印网关启动信息。
     *
     * @param gateway 网关实例
     * @param config 配置对象
     */
    private void printStartupInfo(GatewayService gateway, Config config) {
        // 打印网关基本信息
        printGatewayBasicInfo(config);

        // 打印服务状态
        printServiceStatus();

        // 打印 Web Console 信息
        printWebConsoleInfo(gateway);
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
     */
    private void printWebConsoleInfo(GatewayService gateway) {
        System.out.println("✓ Web Console 已启动");
        System.out.println("  • 访问地址：" + gateway.getWebConsoleUrl());
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
}
