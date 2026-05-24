package io.jobclaw.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * DingTalk Channel - Based on DingTalk Robot Webhook API and Stream Mode
 *
 * Provides DingTalk platform message capabilities:
 * - Webhook mode (passive receiving)
 * - Stream mode (active connection, no public IP required)
 * - Signature verification
 * - Markdown format messages
 * - session_webhook reply mechanism
 */
@Component
public class DingTalkChannel extends BaseChannel {

    private static final Logger logger = LoggerFactory.getLogger(DingTalkChannel.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final String STREAM_CONNECTION_URL = "https://api.dingtalk.com/v1.0/gateway/connections/open";
    private static final long RECONNECT_DELAY_SECONDS = 5;

    private final ChannelsConfig.DingTalkConfig config;
    private final OkHttpClient httpClient;

    // Store session_webhook for replies
    private final Map<String, String> sessionWebhooks = new ConcurrentHashMap<>();

    // Stream mode
    private WebSocket webSocket;
    private volatile boolean streamModeRunning = false;

    public DingTalkChannel(MessageBus messageBus, ChannelsConfig config) {
        super(messageBus, config.getDingtalk().getAllowFrom());
        this.config = config.getDingtalk();
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
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
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
    }

    @Override
    public String getName() {
        return "dingtalk";
    }

    @Override
    public boolean isConfigured() {
        return config.getClientId() != null && !config.getClientId().isEmpty() &&
               config.getClientSecret() != null && !config.getClientSecret().isEmpty();
    }

    @Override
    public void start() {
        logger.info("Starting DingTalk channel...");

        if (!isConfigured()) {
            throw new ChannelException("DingTalk Client ID or Client Secret is empty");
        }

        running = true;

        if (config.isStreamMode()) {
            startStreamMode();
            logger.info("DingTalk channel started (Stream mode)");
        } else {
            logger.info("DingTalk channel started (Webhook mode)");
        }
    }

    private void startStreamMode() {
        streamModeRunning = true;
        Thread streamThread = new Thread(() -> {
            while (running && streamModeRunning) {
                try {
                    connectStreamConnection();
                    break;
                } catch (Exception e) {
                    logger.error("Stream connection failed, retry in {} seconds", RECONNECT_DELAY_SECONDS);
                    try {
                        Thread.sleep(RECONNECT_DELAY_SECONDS * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "DingTalkStreamThread");
        streamThread.setDaemon(true);
        streamThread.start();
    }

    private void connectStreamConnection() {
        StreamConnectionInfo connectionInfo = registerStreamConnection();

        String websocketUrl = connectionInfo.endpoint + "?ticket=" + connectionInfo.ticket;
        logger.info("Connecting to DingTalk Stream service: {}", websocketUrl);

        Request request = new Request.Builder().url(websocketUrl).build();

        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                logger.info("DingTalk Stream connection established");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleStreamMessage(text);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                logger.info("DingTalk Stream closing: code={}, reason={}", code, reason);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                logger.info("DingTalk Stream closed: code={}, reason={}", code, reason);
                if (streamModeRunning && running) {
                    scheduleReconnect();
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                logger.error("DingTalk Stream failure: {}", t.getMessage());
                if (streamModeRunning && running) {
                    scheduleReconnect();
                }
            }
        };

        webSocket = httpClient.newWebSocket(request, listener);
    }

    private StreamConnectionInfo registerStreamConnection() {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("clientId", config.getClientId());
        requestBody.put("clientSecret", config.getClientSecret());

        // Subscribe to bot callbacks and events
        ArrayNode subscriptions = requestBody.putArray("subscriptions");
        ObjectNode botCallback = subscriptions.addObject();
        botCallback.put("type", "CALLBACK");
        botCallback.put("topic", "/v1.0/im/bot/messages/get");
        ObjectNode eventSubscription = subscriptions.addObject();
        eventSubscription.put("type", "EVENT");
        eventSubscription.put("topic", "*");

        requestBody.put("ua", "jobclaw-sdk-java/1.0.0");

        try {
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            Request request = new Request.Builder()
                .url(STREAM_CONNECTION_URL)
                .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    logger.error("Stream registration failed: HTTP {}, body: {}", response.code(), errorBody);
                    throw new ChannelException("Stream registration failed: HTTP " + response.code());
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                JsonNode responseJson = objectMapper.readTree(responseBody);

                String endpoint = responseJson.path("endpoint").asText(null);
                String ticket = responseJson.path("ticket").asText(null);

                if (endpoint == null || endpoint.isEmpty()) {
                    throw new ChannelException("No endpoint in response");
                }
                if (ticket == null || ticket.isEmpty()) {
                    throw new ChannelException("No ticket in response");
                }

                logger.info("Stream connection registered: {}", endpoint);
                return new StreamConnectionInfo(endpoint, ticket);
            }
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException("Stream registration error: " + e.getMessage(), e);
        }
    }

    private void handleStreamMessage(String text) {
        try {
            JsonNode json = objectMapper.readTree(text);

            JsonNode headers = json.path("headers");
            String topic = headers.path("topic").asText("");
            String messageId = headers.path("messageId").asText("");

            logger.info("Received Stream message: topic={}, messageId={}", topic, messageId);

            if ("ping".equals(topic)) {
                String data = json.path("data").asText("{}");
                sendStreamAck(messageId, data);
                return;
            }

            if ("/v1.0/im/bot/messages/get".equals(topic)) {
                String data = json.path("data").asText("");
                if (data.isEmpty()) {
                    logger.warn("Empty message data");
                    return;
                }

                handleIncomingMessage(data);
                sendStreamAck(messageId, "");
            }
        } catch (Exception e) {
            logger.error("Error processing Stream message: {}", e.getMessage());
        }
    }

    private void sendStreamAck(String messageId, String data) {
        if (webSocket == null || !running) {
            return;
        }

        try {
            ObjectNode ack = objectMapper.createObjectNode();
            ack.put("code", 200);

            ObjectNode ackHeaders = ack.putObject("headers");
            ackHeaders.put("contentType", "application/json");
            ackHeaders.put("messageId", messageId);

            ack.put("message", "OK");
            ack.put("data", data);

            webSocket.send(objectMapper.writeValueAsString(ack));
        } catch (Exception e) {
            logger.error("Error sending Stream ACK: {}", e.getMessage());
        }
    }

    private void scheduleReconnect() {
        if (!streamModeRunning || !running) {
            return;
        }

        Thread reconnectThread = new Thread(() -> {
            try {
                Thread.sleep(RECONNECT_DELAY_SECONDS * 1000);
                if (streamModeRunning && running) {
                    connectStreamConnection();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error("Reconnect failed: {}", e.getMessage());
            }
        }, "DingTalkReconnectThread");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    @Override
    public void stop() {
        logger.info("Stopping DingTalk channel...");

        streamModeRunning = false;

        if (webSocket != null) {
            webSocket.close(1000, "Channel stopped");
            webSocket = null;
        }

        running = false;
        sessionWebhooks.clear();

        logger.info("DingTalk channel stopped");
    }

    @Override
    public void send(OutboundMessage message) {
        if (!running) {
            throw new IllegalStateException("DingTalk channel not running");
        }

        String chatId = message.getChatId();

        // Prefer session_webhook
        String webhook = sessionWebhooks.get(chatId);
        if (webhook == null || webhook.isEmpty()) {
            webhook = config.getWebhook();
        }

        if (webhook == null || webhook.isEmpty()) {
            throw new ChannelException("No session_webhook found for chat: " + chatId);
        }

        logger.info("Sending DingTalk message to: {}", chatId);

        sendMarkdownMessage(webhook, "JobClaw", message.getContent());
    }

    public String handleIncomingMessage(String requestBody) {
        try {
            JsonNode json = objectMapper.readTree(requestBody);

            String content = "";
            JsonNode textNode = json.path("text");
            if (textNode.has("content")) {
                content = textNode.get("content").asText();
            }

            if (content.isEmpty()) {
                return "{\"msgtype\":\"text\",\"text\":{\"content\":\"Received\"}}";
            }

            String senderId = json.path("senderStaffId").asText("unknown");
            String senderNick = json.path("senderNick").asText("Unknown User");
            String chatId = senderId;

            // Handle group chat
            String conversationType = json.path("conversationType").asText("1");
            if (!"1".equals(conversationType)) {
                chatId = json.path("conversationId").asText(senderId);
            }

            // Store session_webhook
            String sessionWebhook = json.path("sessionWebhook").asText(null);
            if (sessionWebhook != null && !sessionWebhook.isEmpty()) {
                sessionWebhooks.put(chatId, sessionWebhook);
            }

            logger.info("Received DingTalk message from: {}, chat: {}", senderNick, chatId);

            Map<String, String> metadata = new HashMap<>();
            metadata.put("sender_name", senderNick);
            metadata.put("conversation_id", json.path("conversationId").asText(""));
            metadata.put("conversation_type", conversationType);
            metadata.put("platform", "dingtalk");
            if (sessionWebhook != null) {
                metadata.put("session_webhook", sessionWebhook);
            }

            handleMessage(senderId, chatId, content, null, metadata);

            return "{\"msgtype\":\"text\",\"text\":{\"content\":\"\"}}";

        } catch (Exception e) {
            logger.error("Error processing DingTalk message: {}", e.getMessage());
            return "{\"msgtype\":\"text\",\"text\":{\"content\":\"Error processing message\"}}";
        }
    }

    private void sendMarkdownMessage(String webhook, String title, String content) {
        ObjectNode markdown = objectMapper.createObjectNode();
        markdown.put("msgtype", "markdown");

        ObjectNode markdownContent = markdown.putObject("markdown");
        markdownContent.put("title", title);
        markdownContent.put("text", content);

        try {
            String jsonBody = objectMapper.writeValueAsString(markdown);

            Request request = new Request.Builder()
                .url(webhook)
                .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new ChannelException("Failed to send DingTalk message: HTTP " + response.code());
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                JsonNode responseJson = objectMapper.readTree(responseBody);

                int errcode = responseJson.path("errcode").asInt(0);
                if (errcode != 0) {
                    String errmsg = responseJson.path("errmsg").asText("Unknown error");
                    throw new ChannelException("Failed to send DingTalk message: " + errmsg);
                }

                logger.debug("DingTalk message sent successfully");
            }
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException("Error sending DingTalk message: " + e.getMessage(), e);
        }
    }

    private static class StreamConnectionInfo {
        final String endpoint;
        final String ticket;

        StreamConnectionInfo(String endpoint, String ticket) {
            this.endpoint = endpoint;
            this.ticket = ticket;
        }
    }
}
