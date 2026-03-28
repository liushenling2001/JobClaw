package io.jobclaw.gateway;

import io.jobclaw.bus.MessageBus;
import io.jobclaw.channels.ChannelManager;
import io.jobclaw.config.Config;
import io.jobclaw.config.ChannelsConfig;
import io.jobclaw.cron.CronService;
import io.jobclaw.heartbeat.HeartbeatService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * 网关服务 - 统一管理网关生命周期
 *
 * 替代原有的 GatewayBootstrap，完全由 Spring 管理
 */
@Component
public class GatewayService {

    private static final Logger logger = LoggerFactory.getLogger(GatewayService.class);

    private static final int HEARTBEAT_INTERVAL_SECONDS = 1800;  // 30 分钟
    private static final String DISPLAY_HOST_REPLACEMENT = "127.0.0.1";

    private final Config config;
    private final MessageBus messageBus;
    private final CronService cronService;
    private final ChannelManager channelManager;
    private final HeartbeatService heartbeatService;

    private CountDownLatch shutdownLatch;
    private volatile boolean started = false;

    public GatewayService(Config config,
                          MessageBus messageBus,
                          CronService cronService,
                          ChannelManager channelManager,
                          HeartbeatService heartbeatService) {
        this.config = config;
        this.messageBus = messageBus;
        this.cronService = cronService;
        this.channelManager = channelManager;
        this.heartbeatService = heartbeatService;
        this.shutdownLatch = new CountDownLatch(1);
    }

    @PostConstruct
    public void init() {
        logger.info("GatewayService initialized");
    }

    /**
     * 启动网关服务
     */
    public void start() {
        if (started) {
            logger.warn("Gateway already started");
            return;
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
                String memoryPath = Paths.get(config.getWorkspacePath(), "memory").toString();
                heartbeatService.setMemoryPath(memoryPath);
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
    }

    /**
     * 等待关闭信号
     */
    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    /**
     * 停止网关服务
     */
    @PreDestroy
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
     * 获取启用的通道列表
     */
    public List<String> getEnabledChannels() {
        if (channelManager == null) {
            return List.of();
        }
        return channelManager.getAllChannels().stream()
                .filter(io.jobclaw.channels.Channel::isConnected)
                .map(io.jobclaw.channels.Channel::getName)
                .toList();
    }

    /**
     * 获取 Web Console 地址
     */
    public String getWebConsoleUrl() {
        String host = normalizeDisplayHost(config.getGateway().getHost());
        return String.format("http://%s:%d", host, config.getGateway().getPort());
    }

    /**
     * 获取 Webhook URL
     */
    public String getWebhookUrl() {
        String host = normalizeDisplayHost(config.getGateway().getHost());
        return String.format("http://%s:%d", host, config.getGateway().getPort());
    }

    public boolean isStarted() {
        return started;
    }

    private void stopService(String serviceName, Runnable stopAction, boolean shouldStop) {
        if (shouldStop) {
            try {
                stopAction.run();
            } catch (Exception e) {
                logger.warn("Failed to stop {}: {}", serviceName, e.getMessage());
            }
        }
    }

    private String normalizeDisplayHost(String host) {
        return "0.0.0.0".equals(host) ? DISPLAY_HOST_REPLACEMENT : host;
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在关闭...");
            stop();
            System.out.println("✓ 网关已停止");
        }));
    }
}
