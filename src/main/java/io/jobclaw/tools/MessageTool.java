package io.jobclaw.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 消息发送工具
 * 
 * 用于通过通道发送消息到特定用户或群组
 */
@Component
public class MessageTool {

    @Tool(name = "message", description = "Send a message to a specific channel and chat. Use this tool to deliver responses to users across different messaging platforms (feishu, telegram, whatsapp, discord, etc.).")
    public String sendMessage(
        @ToolParam(description = "Target channel (feishu, telegram, whatsapp, discord, etc.)") String channel,
        @ToolParam(description = "Target chat ID (user ID or group ID)") String chat_id,
        @ToolParam(description = "Message content to send") String content
    ) {
        if (content == null || content.isEmpty()) {
            return "Error: content parameter is required";
        }

        // TODO: Integrate with actual MessageBus/ChannelManager
        // For now, return placeholder response
        String targetChannel = (channel != null && !channel.isEmpty()) ? channel : "current";
        String targetChat = (chat_id != null && !chat_id.isEmpty()) ? chat_id : "current";

        return "Message prepared for delivery:\n" +
               "  Channel: " + targetChannel + "\n" +
               "  Chat ID: " + targetChat + "\n" +
               "  Content: " + content + "\n\n" +
               "Note: Message delivery pending MessageBus integration.";
    }
}
