package io.jobclaw.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.bus.OutboundMessage;
import io.jobclaw.config.ChannelsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import okhttp3.*;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WhatsApp Channel - Based on WhatsApp Bridge WebSocket
 *
 * Provides message sending/receiving via WhatsApp Bridge service:
 * - WebSocket connection to Bridge service
 * - Text message support
 * - Media message support
 */
@Component
public class WhatsAppChannel extends BaseChannel {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppChannel.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ChannelsConfig.WhatsAppConfig config;
    private final OkHttpClient httpClient;
    private WebSocket webSocket;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    public WhatsAppChannel(MessageBus messageBus, ChannelsConfig config) {
        super(messageBus, config.getWhatsapp().getAllowFrom());
        this.config = config.getWhatsapp();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getName() {
        return "whatsapp";
    }

    @Override
    public void start() {
        logger.info("Starting WhatsApp channel, bridge URL: {}", config.getBridgeUrl());

        if (config.getBridgeUrl() == null || config.getBridgeUrl().isEmpty()) {
            throw new ChannelException("WhatsApp Bridge URL is empty");
        }

        CountDownLatch connectLatch = new CountDownLatch(1);

        Request request = new Request.Builder().url(config.getBridgeUrl()).build();
        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                connected.set(true);
                logger.info("WhatsApp Bridge connected");
                connectLatch.countDown();
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleIncomingMessage(text);
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                connected.set(false);
                logger.warn("WhatsApp Bridge closing, code: {}, reason: {}", code, reason);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                connected.set(false);
                String errMsg = t.getMessage() != null ? t.getMessage() : "unknown";
                logger.error("WhatsApp Bridge failed: {}", errMsg);
                connectLatch.countDown();
            }
        });

        try {
            boolean ok = connectLatch.await(15, TimeUnit.SECONDS);
            if (!ok || !connected.get()) {
                throw new ChannelException("WhatsApp Bridge connection timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException("WhatsApp Bridge connection interrupted", e);
        }

        running = true;
        logger.info("WhatsApp channel started");
    }

    @Override
    public void stop() {
        logger.info("Stopping WhatsApp channel...");
        running = false;
        connected.set(false);
        if (webSocket != null) {
            webSocket.close(1000, "Normal closure");
        }
        logger.info("WhatsApp channel stopped");
    }

    @Override
    public void send(OutboundMessage message) {
        if (!running || !connected.get()) {
            throw new IllegalStateException("WhatsApp channel not running");
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "message");
        payload.put("to", message.getChatId());
        payload.put("content", message.getContent());

        try {
            webSocket.send(objectMapper.writeValueAsString(payload));
            logger.debug("WhatsApp message sent to: {}", message.getChatId());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ChannelException("Failed to serialize WhatsApp message", e);
        }
    }

    private void handleIncomingMessage(String messageJson) {
        try {
            JsonNode msg = objectMapper.readTree(messageJson);

            String msgType = msg.path("type").asText("");
            if (!"message".equals(msgType)) {
                return;
            }

            String senderId = msg.path("from").asText(null);
            if (senderId == null || senderId.isEmpty()) {
                return;
            }

            String chatId = msg.path("chat").asText(senderId);
            String content = msg.path("content").asText("");

            List<String> mediaPaths = new ArrayList<>();
            JsonNode mediaNode = msg.path("media");
            if (mediaNode.isArray()) {
                for (JsonNode m : mediaNode) {
                    mediaPaths.add(m.asText());
                }
            }

            Map<String, String> metadata = new HashMap<>();
            if (msg.has("id")) metadata.put("message_id", msg.get("id").asText());
            if (msg.has("from_name")) metadata.put("user_name", msg.get("from_name").asText());

            logger.info("Received WhatsApp message from: {}, chat: {}", senderId, chatId);

            handleMessage(senderId, chatId, content, mediaPaths, metadata);

        } catch (Exception e) {
            logger.error("Error processing WhatsApp message", e);
        }
    }
}
