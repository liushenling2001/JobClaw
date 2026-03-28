package io.jobclaw.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.bus.OutboundMessage;
import io.jobclaw.config.ChannelsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 飞书通道实现 - 基于飞书开放平台官方 SDK
 *
 * 提供飞书/Lark 平台的消息处理能力，支持：
 * - 使用官方 SDK API 发送消息
 * - WebSocket 长连接接收消息（无需公网 IP）
 * - Webhook 接收消息（需配合外部 HTTP 服务）
 *
 * 核心流程：
 * 1. 使用官方 oapi-sdk 建立 WebSocket 长连接
 * 2. 通过 EventDispatcher 订阅和处理事件
 * 3. 使用 SDK 的 API Client 发送消息
 *
 * 配置要求：
 * - App ID：飞书应用的 App ID
 * - App Secret：飞书应用的 App Secret
 * - connectionMode：连接模式，"websocket"（默认）或 "webhook"
 *
 * 飞书开放平台配置步骤：
 * 1. 访问 https://open.feishu.cn/app 创建企业自建应用
 * 2. 在"凭证与基础信息"中获取 App ID 和 App Secret
 * 3. 在"事件订阅"中选择"使用长连接接收事件"
 * 4. 订阅 "接收消息 v2.0" (im.message.receive_v1) 事件
 * 5. 在"机器人"能力中启用机器人
 */
@Component
public class FeishuChannel extends BaseChannel {

    private static final Logger logger = LoggerFactory.getLogger(FeishuChannel.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ChannelsConfig.FeishuConfig config;

    // 飞书官方 SDK 客户端 - API Client 用于发送消息
    private Client apiClient;

    // WebSocket Client 用于接收消息
    private com.lark.oapi.ws.Client wsClient;
    private EventDispatcher eventDispatcher;

    /**
     * 创建飞书通道
     *
     * @param messageBus 消息总线
     * @param config 飞书配置
     */
    public FeishuChannel(MessageBus messageBus, ChannelsConfig config) {
        super(messageBus, config.getFeishu().getAllowFrom());
        this.config = config.getFeishu();
    }

    @Override
    public String getName() {
        return "feishu";
    }

    @Override
    public boolean isConfigured() {
        return config.getAppId() != null && !config.getAppId().isEmpty() &&
               config.getAppSecret() != null && !config.getAppSecret().isEmpty();
    }

    @Override
    public void start() {
        logger.info("正在启动飞书通道...");

        if (!isConfigured()) {
            throw new ChannelException("飞书 App ID 或 App Secret 为空");
        }

        if (config.isWebSocketMode()) {
            startWebSocketMode();
        } else {
            startWebhookMode();
        }

        running = true;
    }

    /**
     * 启动 WebSocket 模式（使用官方 SDK）
     */
    private void startWebSocketMode() {
        logger.info("飞书通道以 WebSocket 模式启动");

        try {
            // 1. 创建 API Client 用于发送消息
            this.apiClient = new Client.Builder(config.getAppId(), config.getAppSecret())
                    .build();

            // 2. 创建事件处理器 - 长连接模式下这两个参数为空字符串
            // 参考官方文档：https://open.feishu.cn/document/server-side-sdk/java-sdk-guide/handle-events
            // 参考官方示例：https://github.com/larksuite/oapi-sdk-java/blob/v2.5.3/sample/src/main/java/com/lark/oapi/sample/ws/Sample.java
            this.eventDispatcher = EventDispatcher.newBuilder("", "")
                    // 使用 onP2MessageReceiveV1 监听 im.message.receive_v1 事件（v2.0 事件结构）
                    .onP2MessageReceiveV1(new com.lark.oapi.service.im.ImService.P2MessageReceiveV1Handler() {
                        @Override
                        public void handle(P2MessageReceiveV1 event) throws Exception {
                            handleIncomingMessageEvent(event);
                        }
                    })
                    .build();

            // 3. 创建 WebSocket 客户端用于接收消息
            this.wsClient = new com.lark.oapi.ws.Client.Builder(config.getAppId(), config.getAppSecret())
                    .eventHandler(eventDispatcher)
                    .build();

            // 4. 启动 WebSocket 长连接（阻塞式启动，在后台线程运行）
            Thread wsThread = new Thread(() -> {
                try {
                    wsClient.start();
                } catch (Exception e) {
                    logger.error("飞书 WebSocket 连接异常", e);
                    if (running) {
                        scheduleReconnect();
                    }
                }
            });
            wsThread.setDaemon(true);
            wsThread.setName("FeishuWebSocketClient");
            wsThread.start();

            logger.info("飞书 WebSocket 长连接已启动（官方 SDK）");

        } catch (Exception e) {
            throw new ChannelException("启动飞书 WebSocket 模式失败", e);
        }
    }

    /**
     * 启动 Webhook 模式
     */
    private void startWebhookMode() {
        logger.info("飞书通道以 Webhook 模式启动");
        logger.info("飞书通道已启动（HTTP API 模式）");
        logger.info("请配合 Webhook 服务使用以接收消息");
    }

    @Override
    public void stop() {
        logger.info("正在停止飞书通道...");
        running = false;

        // 关闭 WebSocket 连接
        if (wsClient != null) {
            try {
                // 使用反射调用 disconnect 方法（SDK 中为 protected）
                java.lang.reflect.Method disconnectMethod = com.lark.oapi.ws.Client.class.getDeclaredMethod("disconnect");
                disconnectMethod.setAccessible(true);
                disconnectMethod.invoke(wsClient);
                logger.info("飞书 WebSocket 连接已关闭");
            } catch (Exception e) {
                logger.warn("关闭飞书 WebSocket 连接失败", e);
            }
            wsClient = null;
        }

        apiClient = null;
        eventDispatcher = null;

        logger.info("飞书通道已停止");
    }

    @Override
    public void send(OutboundMessage message) {
        if (!running) {
            throw new IllegalStateException("飞书通道未运行");
        }

        String chatId = message.getChatId();
        if (chatId == null || chatId.isEmpty()) {
            throw new IllegalArgumentException("Chat ID 为空");
        }

        try {
            // 使用官方 SDK 发送消息
            String content = "{\"text\":\"" + escapeJson(message.getContent()) + "\"}";

            CreateMessageResp resp = apiClient.im().message().create(
                    CreateMessageReq.newBuilder()
                            .receiveIdType("chat_id")
                            .createMessageReqBody(
                                    CreateMessageReqBody.newBuilder()
                                            .msgType("text")
                                            .content(content)
                                            .receiveId(chatId)
                                            .build()
                            )
                            .build()
            );

            // 检查响应状态
            if (resp.getCode() != 0) {
                throw new ChannelException("发送飞书消息失败：" + resp.getMsg());
            }

            logger.debug("飞书消息发送成功：{}", chatId);

        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException("发送飞书消息失败：网络错误", e);
        }
    }

    /**
     * 处理接收到的飞书消息 JSON（仅用于日志等辅助功能）
     *
     * @param messageJson 消息 JSON 字符串
     */
    public void handleIncomingMessage(String messageJson) {
        try {
            JsonNode json = objectMapper.readTree(messageJson);

            // 提取事件数据
            JsonNode eventNode = json.path("event");
            JsonNode sender;
            JsonNode message;

            if (eventNode.isMissingNode() || eventNode.isNull()) {
                // 简化格式，直接使用根节点
                sender = json.path("sender");
                message = json.path("message");
            } else {
                // 完整格式，使用 event 字段
                sender = eventNode.path("sender");
                message = eventNode.path("message");
            }

            String chatId = message.path("chat_id").asText(null);
            if (chatId == null || chatId.isEmpty()) {
                return;
            }

            // 提取发送者 ID
            String senderId = extractSenderId(sender);
            if (senderId.isEmpty()) {
                senderId = "unknown";
            }

            // 提取消息内容
            String content = extractMessageContent(message);
            if (content.isEmpty()) {
                content = "[空消息]";
            }

            logger.info("收到飞书消息：sender={}, chat={}, preview={}",
                    senderId, chatId, truncate(content, 80));

        } catch (Exception e) {
            logger.error("处理飞书消息 JSON 时出错：{}", e.getMessage());
        }
    }

    /**
     * 处理接收到的飞书消息事件（WebSocket 模式）
     */
    private void handleIncomingMessageEvent(P2MessageReceiveV1 event) throws Exception {
        try {
            // 使用 SDK 的 JSON 序列化，然后解析
            String eventJson = Jsons.DEFAULT.toJson(event.getEvent());
            logger.debug("收到飞书事件：requestId={}, data={}",
                    event.getRequestId(),
                    eventJson.length() > 200 ? eventJson.substring(0, 200) + "..." : eventJson);

            // 解析 JSON 并提取数据
            JsonNode json = objectMapper.readTree(eventJson);
            JsonNode sender = json.path("sender");
            JsonNode message = json.path("message");

            if (message == null || message.isMissingNode()) {
                logger.warn("飞书消息数据为空");
                return;
            }

            String chatId = message.path("chat_id").asText(null);
            if (chatId == null || chatId.isEmpty()) {
                logger.warn("飞书消息 Chat ID 为空：messageId={}",
                        message.path("message_id").asText(""));
                return;
            }

            // 提取发送者 ID
            String senderId = extractSenderId(sender);
            if (senderId.isEmpty()) {
                senderId = "unknown";
            }

            // 提取消息内容
            String content = extractMessageContent(message);
            if (content.isEmpty()) {
                content = "[空消息]";
            }

            // 构建元数据
            Map<String, String> metadata = new HashMap<>();
            metadata.put("message_id", message.path("message_id").asText(""));
            metadata.put("message_type", message.path("message_type").asText(""));
            metadata.put("chat_type", message.path("chat_type").asText(""));
            if (sender.has("tenant_key")) {
                metadata.put("tenant_key", sender.get("tenant_key").asText(""));
            }

            logger.info("收到飞书消息：sender={}, chat={}, preview={}",
                    senderId, chatId, truncate(content, 80));

            // 通过父类统一处理权限校验和消息发布
            handleMessage(senderId, chatId, content, null, metadata);

        } catch (Exception e) {
            logger.error("处理飞书 WebSocket 事件时出错：{}", e.getMessage());
            throw e;
        }
    }

    /**
     * 计划重连
     */
    private void scheduleReconnect() {
        // 简单重连逻辑：等待 5 秒后重新启动
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                if (running) {
                    logger.info("尝试重新连接飞书 WebSocket");
                    startWebSocketMode();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    /**
     * 提取发送者 ID（从 JSON）
     */
    private String extractSenderId(JsonNode sender) {
        if (sender == null || !sender.has("sender_id")) {
            return "";
        }

        JsonNode senderId = sender.get("sender_id");

        // 优先使用 user_id
        if (senderId.has("user_id") && !senderId.get("user_id").asText().isEmpty()) {
            return senderId.get("user_id").asText();
        }

        // 其次使用 open_id
        if (senderId.has("open_id") && !senderId.get("open_id").asText().isEmpty()) {
            return senderId.get("open_id").asText();
        }

        // 最后使用 union_id
        if (senderId.has("union_id") && !senderId.get("union_id").asText().isEmpty()) {
            return senderId.get("union_id").asText();
        }

        return "";
    }

    /**
     * 提取消息内容（从 JSON，Webhook 模式兼容）
     */
    private String extractMessageContent(JsonNode message) {
        if (message == null || !message.has("content")) {
            return "";
        }

        String contentStr = message.get("content").asText("");

        // 处理文本消息
        if ("text".equals(message.path("message_type").asText(""))) {
            try {
                JsonNode contentNode = objectMapper.readTree(contentStr);
                if (contentNode.has("text")) {
                    return contentNode.get("text").asText();
                }
            } catch (Exception e) {
                // 解析失败，返回原始内容
            }
        }

        return contentStr;
    }

    /**
     * JSON 字符串转义
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 截断字符串
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
