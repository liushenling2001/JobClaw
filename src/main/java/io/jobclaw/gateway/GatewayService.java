package io.jobclaw.gateway;

import io.jobclaw.agent.AgentOrchestrator;
import io.jobclaw.bus.InboundMessage;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.bus.OutboundMessage;
import io.jobclaw.channels.ChannelManager;
import io.jobclaw.config.Config;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private final AgentOrchestrator agentOrchestrator;
    private final ExecutorService inboundExecutor;

    private CountDownLatch shutdownLatch;
    private volatile boolean started = false;
    private volatile boolean inboundWorkerRunning = false;

    public GatewayService(Config config,
                          MessageBus messageBus,
                          CronService cronService,
                          ChannelManager channelManager,
                          HeartbeatService heartbeatService,
                          AgentOrchestrator agentOrchestrator) {
        this.config = config;
        this.messageBus = messageBus;
        this.cronService = cronService;
        this.channelManager = channelManager;
        this.heartbeatService = heartbeatService;
        this.agentOrchestrator = agentOrchestrator;
        this.inboundExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "gateway-inbound-worker");
            t.setDaemon(true);
            return t;
        });
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
            printChannelStartReport();
        }
        startInboundWorker();

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
        inboundWorkerRunning = false;
        inboundExecutor.shutdownNow();
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
        return channelManager.getLastStartReport().getStartedChannels();
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

    private void startInboundWorker() {
        if (inboundWorkerRunning) {
            return;
        }

        inboundWorkerRunning = true;
        inboundExecutor.submit(() -> {
            logger.info("Inbound message worker started");
            while (inboundWorkerRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    InboundMessage inbound = messageBus.consumeInbound(1, TimeUnit.SECONDS);
                    if (inbound == null) {
                        continue;
                    }
                    handleInboundMessage(inbound);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    logger.error("Failed to process inbound message", e);
                }
            }
            logger.info("Inbound message worker stopped");
        });
    }

    private void handleInboundMessage(InboundMessage inbound) {
        String sessionKey = inbound.getSessionKey() != null && !inbound.getSessionKey().isBlank()
                ? inbound.getSessionKey()
                : inbound.getChannel() + ":" + inbound.getChatId();

        String response;
        if (InboundMessage.COMMAND_NEW_SESSION.equals(inbound.getCommand())) {
            response = "Started a new session. Continue in this chat.";
        } else {
            logger.info("Processing inbound message via agent: channel={}, chatId={}, sessionKey={}",
                    inbound.getChannel(), inbound.getChatId(), sessionKey);
            response = agentOrchestrator.process(sessionKey, inbound.getContent());
        }

        if (response == null || response.isBlank()) {
            logger.debug("Agent returned empty response for session {}", sessionKey);
            return;
        }

        messageBus.publishOutbound(new OutboundMessage(
                inbound.getChannel(),
                inbound.getChatId(),
                response
        ));
    }

    private void printChannelStartReport() {
        if (channelManager == null) {
            return;
        }

        var report = channelManager.getLastStartReport();
        if (!report.getStartedChannels().isEmpty()) {
            System.out.println("✓ 已启动通道：" + String.join(", ", report.getStartedChannels()));
        } else {
            System.out.println("⚠ 警告：没有启动任何通道");
        }

        if (!report.getSkippedChannels().isEmpty()) {
            System.out.println("• 跳过通道：" + String.join(", ", report.getSkippedChannels()));
        }

        if (!report.getFailedChannels().isEmpty()) {
            System.out.println("⚠ 启动失败通道：");
            report.getFailedChannels().forEach((name, reason) ->
                    System.out.println("  - " + name + ": " + reason));
        }
    }
}
