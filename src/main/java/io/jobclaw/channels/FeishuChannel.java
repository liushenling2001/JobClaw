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

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Feishu/Lark Channel - Based on Feishu Open Platform
 *
 * Provides message capabilities via Feishu platform:
 * - HTTP API for sending messages
 * - WebSocket for receiving messages (no public IP required)
 * - Webhook mode support
 */
@Component
public class FeishuChannel extends BaseChannel {

    private static final Logger logger = LoggerFactory.getLogger(FeishuChannel.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000L;
    private static final long MAX_RECONNECT_DELAY_MS = 60000L;

    private static final String API_BASE_URL = "https://open.feishu.cn/open-apis";

    private final ChannelsConfig.FeishuConfig config;
    private final OkHttpClient httpClient;

    private String tenantAccessToken;
    private long tokenExpireTime;

    private WebSocket webSocket;
    private Thread heartbeatThread;
    private volatile boolean webSocketRunning;
    private long pingInterval = 30000;

    private int reconnectAttempts = 0;

    public FeishuChannel(MessageBus messageBus, ChannelsConfig config) {
        super(messageBus, config.getFeishu().getAllowFrom());
        this.config = config.getFeishu();
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(createSslSocketFactory().getSocketFactory(), createTrustManager())
            .build();
    }

    private SSLContext createSslSocketFactory() {
        try {
            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, new TrustManager[]{createTrustManager()}, new SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new ChannelException("Failed to create SSL context", e);
        }
    }

    private X509TrustManager createTrustManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
    }

    @Override
    public String getName() {
        return "feishu";
    }

    @Override
    public void start() {
        logger.info("Starting Feishu channel...");

        if (config.getAppId() == null || config.getAppId().isEmpty() ||
            config.getAppSecret() == null || config.getAppSecret().isEmpty()) {
            throw new ChannelException("Feishu App ID or App Secret is empty");
        }

        if (config.isWebSocketMode()) {
            startWebSocketMode();
        } else {
            startWebhookMode();
        }

        running = true;
    }

    private void startWebSocketMode() {
        logger.info("Feishu channel starting in WebSocket mode");

        try {
            refreshTenantAccessToken();
            String wsUrl = fetchWebSocketEndpoint();
            connectWebSocket(wsUrl);
            startHeartbeat();
            logger.info("Feishu channel started (WebSocket mode)");
        } catch (Exception e) {
            throw new ChannelException("Failed to start Feishu WebSocket mode", e);
        }
    }

    private void startWebhookMode() {
        logger.info("Feishu channel starting in Webhook mode");

        try {
            refreshTenantAccessToken();
        } catch (Exception e) {
            throw new ChannelException("Failed to start Feishu Webhook mode", e);
        }

        logger.info("Feishu channel started (HTTP API mode)");
    }

    @Override
    public void stop() {
        logger.info("Stopping Feishu channel...");
        running = false;

        if (webSocket != null) {
            webSocket.close(1000, "Shutdown");
            webSocket = null;
        }

        webSocketRunning = false;

        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            heartbeatThread = null;
        }

        tenantAccessToken = null;
        reconnectAttempts = 0;

        logger.info("Feishu channel stopped");
    }

    @Override
    public void send(OutboundMessage message) {
        if (!running) {
            throw new IllegalStateException("Feishu channel not running");
        }

        if (tenantAccessToken == null || System.currentTimeMillis() >= tokenExpireTime) {
            refreshTenantAccessToken();
        }

        String chatId = message.getChatId();
        if (chatId == null || chatId.isEmpty()) {
            throw new IllegalArgumentException("Chat ID is empty");
        }

        String content = String.format("{\"text\":\"%s\"}", escapeJson(message.getContent()));

        ObjectNode body = objectMapper.createObjectNode();
        body.put("receive_id", chatId);
        body.put("msg_type", "text");
        body.put("content", content);
        body.put("uuid", "jobclaw-" + System.nanoTime());

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ChannelException("Failed to serialize Feishu message", e);
        }

        String url = API_BASE_URL + "/im/v1/messages?receive_id_type=chat_id";

        Request request = new Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + tenantAccessToken)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                throw new ChannelException("Failed to send Feishu message: HTTP " + response.code() + " " + errorBody);
            }
            logger.debug("Feishu message sent to: {}", chatId);
        } catch (java.io.IOException e) {
            throw new ChannelException("Failed to send Feishu message: network error", e);
        }
    }

    private void refreshTenantAccessToken() {
        String url = API_BASE_URL + "/auth/v3/tenant_access_token/internal";

        ObjectNode body = objectMapper.createObjectNode();
        body.put("app_id", config.getAppId());
        body.put("app_secret", config.getAppSecret());

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ChannelException("Failed to serialize Feishu token request", e);
        }

        Request request = new Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "{}";

            if (!response.isSuccessful()) {
                logger.error("Failed to get Feishu access token: HTTP {}, response: {}", response.code(), responseBody);
                throw new ChannelException("Failed to get Feishu access token: HTTP " + response.code());
            }

            JsonNode json = objectMapper.readTree(responseBody);
            int code = json.path("code").asInt(-1);
            if (code != 0) {
                String msg = json.path("msg").asText("Unknown error");
                throw new ChannelException("Failed to get Feishu access token: " + msg);
            }

            tenantAccessToken = json.path("tenant_access_token").asText(null);
            int expire = json.path("expire").asInt(7200);
            tokenExpireTime = System.currentTimeMillis() + (expire - 300) * 1000L;

            if (tenantAccessToken == null) {
                throw new ChannelException("Failed to get Feishu access token: no tenant_access_token in response");
            }

            logger.debug("Feishu access token refreshed, expires in {} seconds", expire);
        } catch (java.io.IOException e) {
            throw new ChannelException("Failed to get Feishu access token: network error", e);
        }
    }

    private String fetchWebSocketEndpoint() {
        String url = API_BASE_URL + "/callback/ws/endpoint";

        ObjectNode body = objectMapper.createObjectNode();
        body.put("app_id", config.getAppId());
        body.put("app_secret", config.getAppSecret());

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ChannelException("Failed to serialize Feishu WebSocket endpoint request", e);
        }

        Request request = new Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + tenantAccessToken)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ChannelException("Failed to get Feishu WebSocket endpoint: HTTP " + response.code());
            }

            String responseBody = response.body() != null ? response.body().string() : "{}";
            JsonNode json = objectMapper.readTree(responseBody);

            int code = json.path("code").asInt(-1);
            if (code != 0) {
                String msg = json.path("msg").asText("Unknown error");
                throw new ChannelException("Failed to get Feishu WebSocket endpoint: " + msg);
            }

            JsonNode data = json.path("data");
            String wsUrl = data.path("URL").asText(null);

            if (wsUrl == null) {
                throw new ChannelException("Failed to get Feishu WebSocket endpoint: no URL in response");
            }

            JsonNode clientConfig = data.path("ClientConfig");
            if (clientConfig.has("PingInterval")) {
                pingInterval = clientConfig.get("PingInterval").asLong(30000);
            }

            logger.debug("Feishu WebSocket endpoint obtained: {}", wsUrl);
            return wsUrl;
        } catch (java.io.IOException e) {
            throw new ChannelException("Failed to get Feishu WebSocket endpoint: network error", e);
        }
    }

    private void connectWebSocket(String wsUrl) {
        webSocketRunning = true;

        Request request = new Request.Builder().url(wsUrl).build();

        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                logger.info("Feishu WebSocket connection established");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleWebSocketMessage(text);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                logger.info("Feishu WebSocket closing: code={}, reason={}", code, reason);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                logger.info("Feishu WebSocket closed: code={}, reason={}", code, reason);
                webSocketRunning = false;
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                logger.error("Feishu WebSocket failure: {}", t.getMessage());
                webSocketRunning = false;

                if (running) {
                    scheduleReconnect();
                }
            }
        };

        webSocket = httpClient.newWebSocket(request, listener);
    }

    private void startHeartbeat() {
        heartbeatThread = new Thread(() -> {
            while (webSocketRunning && running) {
                try {
                    Thread.sleep(pingInterval);

                    if (webSocketRunning && webSocket != null) {
                        ObjectNode pingMessage = objectMapper.createObjectNode();
                        pingMessage.put("type", "ping");
                        webSocket.send(pingMessage.toString());
                        logger.debug("WebSocket ping sent");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Failed to send WebSocket ping: {}", e.getMessage());
                }
            }
        });

        heartbeatThread.setDaemon(true);
        heartbeatThread.setName("FeishuWebSocketHeartbeat");
        heartbeatThread.start();
    }

    private void scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            logger.error("Feishu WebSocket reconnect attempts exhausted (max: {})", MAX_RECONNECT_ATTEMPTS);
            return;
        }

        long delay = Math.min(INITIAL_RECONNECT_DELAY_MS * (1L << reconnectAttempts), MAX_RECONNECT_DELAY_MS);

        new Thread(() -> {
            try {
                Thread.sleep(delay);

                if (running) {
                    reconnectAttempts++;
                    logger.info("Attempting Feishu WebSocket reconnect (attempt {}/{}, delay: {}ms)",
                            reconnectAttempts, MAX_RECONNECT_ATTEMPTS, delay);

                    try {
                        refreshTenantAccessToken();
                        String wsUrl = fetchWebSocketEndpoint();
                        connectWebSocket(wsUrl);
                        startHeartbeat();

                        reconnectAttempts = 0;
                        logger.info("Feishu WebSocket reconnected successfully");
                    } catch (Exception e) {
                        logger.error("Feishu WebSocket reconnect failed (attempt {}): {}", reconnectAttempts, e.getMessage());
                        scheduleReconnect();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void handleWebSocketMessage(String text) {
        try {
            JsonNode json = objectMapper.readTree(text);
            String type = json.path("type").asText("");

            if ("pong".equals(type)) {
                return;
            }

            if ("event".equals(type)) {
                handleIncomingMessage(text);
            }
        } catch (Exception e) {
            logger.error("Error processing WebSocket message: {}", e.getMessage());
        }
    }

    public void handleIncomingMessage(String messageJson) {
        try {
            JsonNode json = objectMapper.readTree(messageJson);

            JsonNode event = json.path("event");
            JsonNode message = event.path("message");
            JsonNode sender = event.path("sender");

            String chatId = message.path("chat_id").asText(null);
            if (chatId == null || chatId.isEmpty()) {
                return;
            }

            String senderId = extractSenderId(sender);
            if (senderId.isEmpty()) {
                senderId = "unknown";
            }

            String content = extractMessageContent(message);
            if (content.isEmpty()) {
                content = "[Empty message]";
            }

            Map<String, String> metadata = new HashMap<>();
            if (message.has("message_id")) {
                metadata.put("message_id", message.get("message_id").asText());
            }
            if (message.has("message_type")) {
                metadata.put("message_type", message.get("message_type").asText());
            }
            if (message.has("chat_type")) {
                metadata.put("chat_type", message.get("chat_type").asText());
            }
            if (sender.has("tenant_key")) {
                metadata.put("tenant_key", sender.get("tenant_key").asText());
            }

            logger.info("Received Feishu message from: {}, chat: {}", senderId, chatId);

            handleMessage(senderId, chatId, content, null, metadata);

        } catch (Exception e) {
            logger.error("Error processing Feishu message: {}", e.getMessage());
        }
    }

    private String extractSenderId(JsonNode sender) {
        if (sender == null || !sender.has("sender_id")) {
            return "";
        }

        JsonNode senderId = sender.get("sender_id");

        if (senderId.has("user_id") && !senderId.get("user_id").asText().isEmpty()) {
            return senderId.get("user_id").asText();
        }
        if (senderId.has("open_id") && !senderId.get("open_id").asText().isEmpty()) {
            return senderId.get("open_id").asText();
        }
        if (senderId.has("union_id") && !senderId.get("union_id").asText().isEmpty()) {
            return senderId.get("union_id").asText();
        }

        return "";
    }

    private String extractMessageContent(JsonNode message) {
        if (message == null || !message.has("content")) {
            return "";
        }

        String contentStr = message.get("content").asText("");

        if ("text".equals(message.path("message_type").asText(""))) {
            try {
                JsonNode contentNode = objectMapper.readTree(contentStr);
                if (contentNode.has("text")) {
                    return contentNode.get("text").asText();
                }
            } catch (Exception e) {
                // Parse failed, return original content
            }
        }

        return contentStr;
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
