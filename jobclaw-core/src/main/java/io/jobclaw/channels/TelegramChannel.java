package io.jobclaw.channels;

import io.jobclaw.bus.InboundMessage;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.bus.OutboundMessage;
import io.jobclaw.config.ChannelsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Telegram Channel - Telegram bot messaging
 */
@Component
public class TelegramChannel extends BaseChannel {

    private static final Logger logger = LoggerFactory.getLogger(TelegramChannel.class);

    private final ChannelsConfig.TelegramConfig config;
    private final String token;

    public TelegramChannel(MessageBus messageBus, ChannelsConfig config) {
        super(messageBus, config.getTelegram().getAllowFrom());
        this.config = config.getTelegram();
        this.token = config.getTelegram().getToken();
    }

    @Override
    public String getName() {
        return "telegram";
    }

    @Override
    public void start() {
        if (!config.isEnabled()) {
            logger.info("Telegram channel is disabled");
            return;
        }

        // Initialize Telegram bot
        // Full implementation would use telegrambots library

        running = true;
        logger.info("Telegram channel started");
    }

    @Override
    public void stop() {
        running = false;
        logger.info("Telegram channel stopped");
    }

    @Override
    public void send(OutboundMessage message) {
        // Implement Telegram message sending
        logger.debug("Sending Telegram message to: {}", message.getChatId());
    }
}
