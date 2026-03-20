package io.jobclaw.bus;

/**
 * 入站消息 - 来自外部通道的用户消息
 */
public class InboundMessage {

    public static final String COMMAND_NEW_SESSION = "new_session";

    private String channel;
    private String senderId;
    private String chatId;
    private String content;
    private java.util.List<String> media;
    private String sessionKey;
    private String command;
    private java.util.Map<String, String> metadata;

    public InboundMessage() {
    }

    public InboundMessage(String channel, String senderId, String chatId, String content) {
        this.channel = channel;
        this.senderId = senderId;
        this.chatId = chatId;
        this.content = content;
        this.sessionKey = channel + ":" + chatId;
    }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public java.util.List<String> getMedia() { return media; }
    public void setMedia(java.util.List<String> media) { this.media = media; }
    public String getSessionKey() { return sessionKey; }
    public void setSessionKey(String sessionKey) { this.sessionKey = sessionKey; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public boolean isCommand() { return command != null && !command.isEmpty(); }
    public java.util.Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(java.util.Map<String, String> metadata) { this.metadata = metadata; }

    @Override
    public String toString() {
        return "InboundMessage{" +
                "channel='" + channel + '\'' +
                ", senderId='" + senderId + '\'' +
                ", chatId='" + chatId + '\'' +
                ", content='" + (content != null && content.length() > 50 ? content.substring(0, 50) + "..." : content) + '\'' +
                ", sessionKey='" + sessionKey + '\'' +
                '}';
    }
}
