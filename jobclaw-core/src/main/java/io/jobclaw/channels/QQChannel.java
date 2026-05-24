package io.jobclaw.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.bus.OutboundMessage;
import io.jobclaw.config.ChannelsConfig;
import okhttp3.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * QQ Channel - Based on Tencent QQ Open Platform API
 *
 * Provides QQ robot message capabilities:
 * - Private message sending/receiving
 * - Group @ message handling
 * - WebSocket message receiving (requires gateway service)
 */
@Component
public class QQChannel extends BaseChannel {

    private static final Logger logger = LoggerFactory.getLogger(QQChannel.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static final String API_BASE_URL = "https://api.sgroup.qq.com";

    private final ChannelsConfig.QQConfig config;
    private final OkHttpClient httpClient;

    private String accessToken;
    private long tokenExpireTime;

    // Processed message IDs for deduplication
    private final Set<String> processedIds = ConcurrentHashMap.newKeySet();

    public QQChannel(MessageBus messageBus, ChannelsConfig config) {
        super(messageBus, config.getQq().getAllowFrom());
        this.config = config.getQq();
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    }

    @Override
    public String getName() {
        return "qq";
    }

    @Override
    public boolean isConfigured() {
        return config.getAppId() != null && !config.getAppId().isEmpty() &&
               config.getAppSecret() != null && !config.getAppSecret().isEmpty();
    }

    @Override
    public void start() {
        logger.info("Starting QQ channel...");

        if (!isConfigured()) {
            throw new ChannelException("QQ App ID or App Secret is empty");
        }

        try {
            refreshAccessToken();
        } catch (Exception e) {
            throw new ChannelException("Failed to get access token", e);
        }

        running = true;
        logger.info("QQ channel started (API mode)");
        logger.info("Please use gateway service to receive messages");
    }

    @Override
    public void stop() {
        logger.info("Stopping QQ channel...");
        running = false;
        accessToken = null;
        processedIds.clear();
        logger.info("QQ channel stopped");
    }

    @Override
    public void send(OutboundMessage message) {
        if (!running) {
            throw new IllegalStateException("QQ channel not running");
        }

        if (accessToken == null || System.currentTimeMillis() >= tokenExpireTime) {
            try {
                refreshAccessToken();
            } catch (Exception e) {
                throw new ChannelException("Failed to refresh access token", e);
            }
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("content", message.getContent());

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ChannelException("Failed to serialize message", e);
        }

        String url = API_BASE_URL + "/v2/users/" + message.getChatId() + "/messages";

        Request request = new Request.Builder()
            .url(url)
            .header("Authorization", "QQBot " + accessToken)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                throw new ChannelException("Failed to send QQ message: HTTP " + response.code() + " " + errorBody);
            }
            logger.debug("QQ message sent to: {}", message.getChatId());
        } catch (IOException e) {
            throw new ChannelException("Network error sending QQ message", e);
        }
    }

    private void refreshAccessToken() throws Exception {
        String url = "https://bots.qq.com/app/getAppAccessToken";

        ObjectNode body = objectMapper.createObjectNode();
        body.put("appId", config.getAppId());
        body.put("clientSecret", config.getAppSecret());

        String jsonBody = objectMapper.writeValueAsString(body);

        Request request = new Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Failed to get QQ access token: HTTP " + response.code());
            }

            String responseBody = response.body() != null ? response.body().string() : "{}";
            JsonNode json = objectMapper.readTree(responseBody);

            accessToken = json.path("access_token").asText(null);
            int expiresIn = json.path("expires_in").asInt(7200);
            tokenExpireTime = System.currentTimeMillis() + (expiresIn - 300) * 1000L;

            if (accessToken == null) {
                throw new Exception("Failed to get QQ access token: no access_token in response");
            }

            logger.debug("QQ access token refreshed, expires in {} seconds", expiresIn);
        }
    }

    public void handleIncomingMessage(String messageJson) {
        try {
            JsonNode json = objectMapper.readTree(messageJson);

            String messageId = json.path("id").asText(null);
            if (messageId == null) {
                return;
            }

            // Deduplication check
            if (processedIds.contains(messageId)) {
                return;
            }
            processedIds.add(messageId);

            // Clean up old message IDs
            if (processedIds.size() > 10000) {
                processedIds.clear();
            }

            JsonNode author = json.path("author");
            String senderId = author.path("id").asText("unknown");
            if (senderId.equals("unknown")) {
                return;
            }

            String content = json.path("content").asText("");
            if (content.isEmpty()) {
                return;
            }

            String chatId = senderId;
            String groupId = json.path("group_id").asText(null);
            if (groupId != null && !groupId.isEmpty()) {
                chatId = groupId;
            }

            logger.info("Received QQ message from: {}, chat: {}", senderId, chatId);

            Map<String, String> metadata = new HashMap<>();
            metadata.put("message_id", messageId);
            if (groupId != null) {
                metadata.put("group_id", groupId);
            }

            handleMessage(senderId, chatId, content, null, metadata);

        } catch (Exception e) {
            logger.error("Error processing QQ message: {}", e.getMessage());
        }
    }
}
