package io.jobclaw.channels;

import io.jobclaw.bus.MessageBus;
import io.jobclaw.bus.OutboundMessage;
import io.jobclaw.config.ChannelsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Discord Channel - Discord bot messaging
 */
@Component
public class DiscordChannel extends BaseChannel {

    private static final Logger logger = LoggerFactory.getLogger(DiscordChannel.class);

    private final ChannelsConfig.DiscordConfig config;
    private final String token;

    public DiscordChannel(MessageBus messageBus, ChannelsConfig config) {
        super(messageBus, config.getDiscord().getAllowFrom());
        this.config = config.getDiscord();
        this.token = config.getDiscord().getToken();
    }

    @Override
    public String getName() {
        return "discord";
    }

    @Override
    public void start() {
        if (!config.isEnabled()) {
            logger.info("Discord channel is disabled");
            return;
        }

        // Initialize Discord bot using JDA
        running = true;
        logger.info("Discord channel started");
    }

    @Override
    public void stop() {
        running = false;
        logger.info("Discord channel stopped");
    }

    @Override
    public void send(OutboundMessage message) {
        logger.debug("Sending Discord message to: {}", message.getChatId());
    }
}
