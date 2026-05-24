package io.jobclaw.channels;

import io.jobclaw.bus.MessageBus;
import io.jobclaw.bus.OutboundMessage;
import io.jobclaw.config.ChannelsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class ChannelManager {

    private static final Logger logger = LoggerFactory.getLogger(ChannelManager.class);

    private final Map<String, Channel> channels;
    private final MessageBus messageBus;
    private final ChannelsConfig channelsConfig;
    private final ScheduledExecutorService dispatcherExecutor;

    private volatile boolean running = false;
    private volatile ChannelStartReport lastStartReport = ChannelStartReport.empty();

    public ChannelManager(MessageBus messageBus,
                          ChannelsConfig channelsConfig,
                          List<Channel> channelList) {
        this.messageBus = messageBus;
        this.channelsConfig = channelsConfig;
        this.channels = new ConcurrentHashMap<>();

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
        initializeChannels();

        List<String> startedChannels = new ArrayList<>();
        List<String> skippedChannels = new ArrayList<>();
        Map<String, String> failedChannels = new LinkedHashMap<>();

        for (Channel channel : channels.values()) {
            try {
                if (!shouldStart(channel)) {
                    logger.debug("Channel {} not configured, skipping startup", channel.getName());
                    skippedChannels.add(channel.getName());
                    continue;
                }

                channel.start();
                startedChannels.add(channel.getName());
                logger.info("Started channel: {}", channel.getName());
            } catch (ChannelException e) {
                failedChannels.put(channel.getName(), e.getMessage());
                logger.debug("Channel {} not started: {}", channel.getName(), e.getMessage());
            } catch (Exception e) {
                failedChannels.put(channel.getName(), e.getMessage());
                logger.error("Failed to start channel: {}", channel.getName(), e);
            }
        }

        lastStartReport = new ChannelStartReport(startedChannels, skippedChannels, failedChannels);
        dispatcherExecutor.scheduleAtFixedRate(this::dispatchOutbound, 0, 100, TimeUnit.MILLISECONDS);
        logger.info("All channels started");
    }

    private boolean shouldStart(Channel channel) {
        return isEnabled(channel.getName()) && channel.isConfigured();
    }

    private boolean isEnabled(String channelName) {
        return switch (channelName) {
            case "telegram" -> channelsConfig.getTelegram().isEnabled();
            case "discord" -> channelsConfig.getDiscord().isEnabled();
            case "feishu" -> channelsConfig.getFeishu().isEnabled();
            case "dingtalk" -> channelsConfig.getDingtalk().isEnabled();
            case "qq" -> channelsConfig.getQq().isEnabled();
            case "whatsapp" -> channelsConfig.getWhatsapp().isEnabled();
            case "maixcam" -> channelsConfig.getMaixcam().isEnabled();
            default -> false;
        };
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
        // Channels are initialized by Spring and filtered here by config.
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

    public ChannelStartReport getLastStartReport() {
        return lastStartReport;
    }

    public static class ChannelStartReport {
        private final List<String> startedChannels;
        private final List<String> skippedChannels;
        private final Map<String, String> failedChannels;

        public ChannelStartReport(List<String> startedChannels,
                                  List<String> skippedChannels,
                                  Map<String, String> failedChannels) {
            this.startedChannels = List.copyOf(startedChannels);
            this.skippedChannels = List.copyOf(skippedChannels);
            this.failedChannels = Map.copyOf(failedChannels);
        }

        public static ChannelStartReport empty() {
            return new ChannelStartReport(List.of(), List.of(), Map.of());
        }

        public List<String> getStartedChannels() {
            return startedChannels;
        }

        public List<String> getSkippedChannels() {
            return skippedChannels;
        }

        public Map<String, String> getFailedChannels() {
            return failedChannels;
        }
    }
}
