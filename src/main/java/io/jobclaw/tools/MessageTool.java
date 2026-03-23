package io.jobclaw.tools;

import io.jobclaw.bus.OutboundMessage;
import io.jobclaw.channels.Channel;
import io.jobclaw.channels.ChannelManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 消息发送工具
 * 
 * 允许 Agent 发送消息到指定通道
 * 
 * 支持的通道：
 * - feishu: 飞书
 * - whatsapp: WhatsApp
 * - telegram: Telegram
 * - discord: Discord
 * - qq: QQ
 * - dingtalk: 钉钉
 * - maixcam: MaixCam
 */
@Component
public class MessageTool {

    private static final Logger logger = LoggerFactory.getLogger(MessageTool.class);

    private final ChannelManager channelManager;

    public MessageTool(ChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    @Tool(name = "message", description = "Send messages to IM channels. Use this when you need to proactively send a message to a user or channel (e.g., notifications, alerts, updates). IMPORTANT: This is for OUTBOUND messages from Agent to users, not for replying to user messages in the current conversation.")
    public String message(
        @ToolParam(description = "Target channel name: feishu, whatsapp, telegram, discord, qq, dingtalk, maixcam") String channel,
        @ToolParam(description = "Message content to send") String content,
        @ToolParam(description = "Target chat ID or user ID (optional for some channels)") String chat_id
    ) {
        if (channel == null || channel.isEmpty()) {
            return "Error: channel parameter is required";
        }

        if (content == null || content.isEmpty()) {
            return "Error: content parameter is required";
        }

        try {
            Channel targetChannel = channelManager.getChannel(channel);
            
            if (targetChannel == null) {
                return "Error: Channel '" + channel + "' not found. Available channels: " + getAvailableChannels();
            }

            if (!targetChannel.isConnected()) {
                return "Error: Channel '" + channel + "' is not connected. Status: " + targetChannel.getClass().getSimpleName();
            }

            // Create and send outbound message
            OutboundMessage message = new OutboundMessage(
                channel,
                chat_id != null ? chat_id : "default",
                content
            );

            targetChannel.send(message);
            
            logger.info("Message sent to channel: {}, chatId: {}, content length: {}", 
                       channel, chat_id, content.length());

            String channelDisplay = getChannelEmoji(channel) + " " + channel;
            String chatIdInfo = chat_id != null ? " to `" + chat_id + "`" : "";
            
            return "✅ Message sent successfully via " + channelDisplay + chatIdInfo + "\n\n" +
                   "**Content preview**: " + (content.length() > 100 ? content.substring(0, 100) + "..." : content);
                   
        } catch (Exception e) {
            logger.error("Failed to send message to channel: {}", channel, e);
            return "Error sending message: " + e.getMessage();
        }
    }

    private String getChannelEmoji(String channel) {
        return switch (channel.toLowerCase()) {
            case "feishu" -> "📱";
            case "whatsapp" -> "💬";
            case "telegram" -> "✈️";
            case "discord" -> "🎮";
            case "qq" -> "🐧";
            case "dingtalk" -> "🔔";
            case "maixcam" -> "📹";
            default -> "📤";
        };
    }

    private String getAvailableChannels() {
        StringBuilder sb = new StringBuilder();
        for (Channel channel : channelManager.getAllChannels()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(channel.getName());
            sb.append(channel.isConnected() ? " ✅" : " ❌");
        }
        return sb.toString();
    }
}
