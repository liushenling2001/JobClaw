package io.jobclaw.channels;

import io.jobclaw.bus.MessageBus;
import io.jobclaw.bus.OutboundMessage;
import io.jobclaw.config.ChannelsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Channel Manager - Manages lifecycle of all messaging channels
 */
@Component
public class ChannelManager {

    private static final Logger logger = LoggerFactory.getLogger(ChannelManager.class);

    private final Map<String, Channel> channels;
    private final MessageBus messageBus;
    private final ChannelsConfig channelsConfig;

    private final ScheduledExecutorService dispatcherExecutor;
    private volatile boolean running = false;

    public ChannelManager(MessageBus messageBus,
                          ChannelsConfig channelsConfig,
                          List<Channel> channelList) {
        this.messageBus = messageBus;
        this.channelsConfig = channelsConfig;
        this.channels = new ConcurrentHashMap<>();

        // Register all injected channels
        for (Channel channel : channelList) {
            register(channel);
        }

        this.dispatcherExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "channel-dispatcher");
            t.setDaemon(true);
            return t;
        });
    }

    public void register(Channel channel) {
        channels.put(channel.getName(), channel);
        logger.info("Registered channel: {}", channel.getName());
    }

    public void startAll() {
        if (running) {
            return;
        }

        running = true;

        // Initialize channels based on config
        initializeChannels();

        // Start all registered channels
        for (Channel channel : channels.values()) {
            try {
                // Skip channels that are not properly configured
                if (!shouldStart(channel)) {
                    logger.debug("Channel {} not configured, skipping registration", channel.getName());
                    continue;
                }
                channel.start();
                logger.info("Started channel: {}", channel.getName());
            } catch (ChannelException e) {
                // ChannelException 表示配置问题，静默跳过不打印错误
                logger.debug("Channel {} not started: {}", channel.getName(), e.getMessage());
            } catch (Exception e) {
                logger.error("Failed to start channel: {}", channel.getName(), e);
            }
        }

        // Start outbound dispatcher
        dispatcherExecutor.scheduleAtFixedRate(this::dispatchOutbound, 0, 100, TimeUnit.MILLISECONDS);

        logger.info("All channels started");
    }

    /**
     * Check if a channel should be started based on its configuration
     */
    private boolean shouldStart(Channel channel) {
        return channel.isConfigured();
    }

    public void stopAll() {
        if (!running) {
            return;
        }

        running = false;
        dispatcherExecutor.shutdown();

        for (Channel channel : channels.values()) {
            try {
                channel.stop();
                logger.info("Stopped channel: {}", channel.getName());
            } catch (Exception e) {
                logger.error("Failed to stop channel: {}", channel.getName(), e);
            }
        }

        logger.info("All channels stopped");
    }

    private void initializeChannels() {
        // Channels will be initialized based on configuration
        // Each channel type checks its enabled flag
    }

    private void dispatchOutbound() {
        try {
            OutboundMessage message = messageBus.subscribeOutbound(50, TimeUnit.MILLISECONDS);
            if (message != null) {
                Channel channel = channels.get(message.getChannel());
                if (channel != null && channel.isConnected()) {
                    channel.send(message);
                } else {
                    logger.warn("No connected channel for: {}", message.getChannel());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Error dispatching outbound message", e);
        }
    }

    public Channel getChannel(String name) {
        return channels.get(name);
    }

    public Collection<Channel> getAllChannels() {
        return channels.values();
    }

    public Map<String, Boolean> getStatus() {
        Map<String, Boolean> status = new HashMap<>();
        for (Channel channel : channels.values()) {
            status.put(channel.getName(), channel.isConnected());
        }
        return status;
    }
}
