package io.jobclaw.channels;

import io.jobclaw.bus.InboundMessage;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.bus.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class BaseChannel implements Channel {

    protected static final Logger logger = LoggerFactory.getLogger(BaseChannel.class);

    protected final MessageBus messageBus;
    protected final List<String> allowFrom;
    protected volatile boolean running = false;

    /**
     * Maps chatId to active sessionKey.
     * When user sends /new command, a new sessionKey is generated for that chatId.
     */
    private final Map<String, String> activeSessionKeys = new ConcurrentHashMap<>();

    public BaseChannel(MessageBus messageBus, List<String> allowFrom) {
        this.messageBus = messageBus;
        this.allowFrom = allowFrom;
    }

    @Override
    public boolean isAllowed(String senderId) {
        if (allowFrom == null || allowFrom.isEmpty()) {
            return true;
        }
        return allowFrom.contains(senderId);
    }

    @Override
    public boolean isConnected() {
        return running;
    }

    /**
     * Handle incoming message with permission check and session management.
     *
     * @param senderId sender identifier
     * @param chatId chat identifier
     * @param content message content
     * @param media optional media paths
     * @param metadata optional metadata
     * @return published InboundMessage, or null if permission denied
     */
    protected InboundMessage handleMessage(String senderId, String chatId, String content,
                                           List<String> media, Map<String, String> metadata) {
        if (!isAllowed(senderId)) {
            logger.debug("Message from unauthorized sender ignored: {}", senderId);
            return null;
        }

        String trimmedContent = content != null ? content.trim() : "";

        // Handle /new command for new session
        if ("/new".equalsIgnoreCase(trimmedContent)) {
            String newSessionKey = generateNewSessionKey(chatId);
            activeSessionKeys.put(chatId, newSessionKey);

            logger.info("Received /new command, new session created for chatId: {}", chatId);

            InboundMessage msg = new InboundMessage(getName(), senderId, chatId, trimmedContent);
            msg.setSessionKey(newSessionKey);
            msg.setCommand(InboundMessage.COMMAND_NEW_SESSION);
            if (metadata != null) {
                msg.setMetadata(metadata);
            }
            messageBus.publishInbound(msg);
            return msg;
        }

        // Use active sessionKey or default
        String sessionKey = activeSessionKeys.getOrDefault(chatId, getName() + ":" + chatId);

        InboundMessage msg = new InboundMessage(getName(), senderId, chatId, content);
        msg.setMedia(media);
        msg.setSessionKey(sessionKey);
        if (metadata != null) {
            msg.setMetadata(metadata);
        }

        messageBus.publishInbound(msg);
        return msg;
    }

    private String generateNewSessionKey(String chatId) {
        return getName() + ":" + chatId + ":" + System.currentTimeMillis();
    }

    protected void publishInbound(InboundMessage message) {
        messageBus.publishInbound(message);
    }

    protected void publishOutbound(OutboundMessage message) {
        messageBus.publishOutbound(message);
    }

    protected void setRunning(boolean running) {
        this.running = running;
    }
}
