package io.jobclaw.cli;

import io.jobclaw.agent.AgentLoop;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.channels.Channel;
import io.jobclaw.channels.ChannelManager;
import io.jobclaw.config.Config;
import io.jobclaw.config.ChannelsConfig;
import io.jobclaw.cron.CronService;
import io.jobclaw.heartbeat.HeartbeatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * 网关服务启动器，负责编排和管理所有服务的生命周期。
 */
public class GatewayBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(GatewayBootstrap.class);

    private static final int HEARTBEAT_INTERVAL_SECONDS = 1800;  // 心跳间隔（30 分钟）
    private static final String HEARTBEAT_SESSION_KEY = "heartbeat:default";
    private static final String DISPLAY_HOST_REPLACEMENT = "127.0.0.1";

    // 配置和核心组件
    private final Config config;
    private final AgentLoop agentLoop;
    private final MessageBus bus;
    private final String workspace;

    // 服务组件
    private ChannelManager channelManager;
    private CronService cronService;
    private HeartbeatService heartbeatService;
    private CountDownLatch shutdownLatch = new CountDownLatch(1);
    private boolean started = false;

    /**
     * 构造网关启动器。
     */
    public GatewayBootstrap(Config config, AgentLoop agentLoop, MessageBus bus) {
        this.config = config;
        this.agentLoop = agentLoop;
        this.bus = bus;
        this.workspace = config.getWorkspacePath();
    }

    /**
     * 初始化所有服务组件。
     */
    public GatewayBootstrap initialize() {
        logger.info("Initializing gateway services");

        // 1. 初始化通道管理器
        channelManager = new ChannelManager(bus, config.getChannels(), List.of());

        // 2. 初始化定时任务服务（由 Spring 管理，此处不手动创建）
        // cronService 将通过 Spring 依赖注入获取

        // 3. 初始化心跳服务
        heartbeatService = new HeartbeatService(
                Paths.get(workspace, "memory").toString(),
                HEARTBEAT_INTERVAL_SECONDS
        );

        logger.info("Gateway services initialized");
        return this;
    }

    /**
     * 启动所有服务。
     */
    public GatewayBootstrap start() {
        if (started) {
            throw new IllegalStateException("Gateway already started");
        }

        logger.info("Starting gateway services");

        // 1. 启动定时任务服务
        if (cronService != null) {
            cronService.start();
            logger.info("Cron service started");
        }

        // 2. 启动心跳服务
        if (heartbeatService != null) {
            try {
                heartbeatService.start();
                logger.info("Heartbeat service started");
            } catch (Exception e) {
                logger.warn("Heartbeat service not started: {}", e.getMessage());
            }
        }

        // 3. 启动所有通道
        if (channelManager != null) {
            channelManager.startAll();
            logger.info("Channel services started");
        }

        // 4. 注册关闭钩子
        registerShutdownHook();

        started = true;
        logger.info("Gateway started successfully");
        return this;
    }

    /**
     * 等待关闭信号。
     */
    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    /**
     * 停止所有服务。
     */
    public void stop() {
        if (!started) {
            return;
        }

        logger.info("Stopping gateway services");

        // 按相反顺序停止服务
        stopService("Heartbeat", () -> {
            if (heartbeatService != null) heartbeatService.stop();
        }, heartbeatService != null);

        stopService("Cron", () -> {
            if (cronService != null) cronService.stop();
        }, cronService != null);

        stopService("Channels", () -> {
            if (channelManager != null) channelManager.stopAll();
        }, channelManager != null);

        shutdownLatch.countDown();
        started = false;
        logger.info("Gateway stopped");
    }

    /**
     * 获取已启用的通道列表。
     */
    public List<String> getEnabledChannels() {
        if (channelManager == null) {
            return List.of();
        }
        return channelManager.getAllChannels().stream()
                .filter(Channel::isConnected)
                .map(Channel::getName)
                .toList();
    }

    /**
     * 停止单个服务。
     */
    private void stopService(String serviceName, Runnable stopAction, boolean shouldStop) {
        if (shouldStop) {
            try {
                stopAction.run();
            } catch (Exception e) {
                logger.warn("Failed to stop {}: {}", serviceName, e.getMessage());
            }
        }
    }

    /**
     * 注册关闭钩子。
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在关闭...");
            stop();
            System.out.println("✓ 网关已停止");
        }));
    }

    /**
     * 规范化显示主机名。
     */
    private String normalizeDisplayHost(String host) {
        return "0.0.0.0".equals(host) ? DISPLAY_HOST_REPLACEMENT : host;
    }

    /**
     * 计算 Web Console 端口。
     * 注意：Spring Boot Web 服务器与网关使用同一端口
     */
    private int calculateWebConsolePort() {
        return config.getGateway().getPort();
    }

    /**
     * 获取 Webhook 服务地址。
     */
    public String getWebhookUrl() {
        String host = normalizeDisplayHost(config.getGateway().getHost());
        return String.format("http://%s:%d", host, config.getGateway().getPort());
    }

    /**
     * 获取 Web Console 服务地址。
     */
    public String getWebConsoleUrl() {
        String host = normalizeDisplayHost(config.getGateway().getHost());
        return String.format("http://%s:%d", host, calculateWebConsolePort());
    }
}
