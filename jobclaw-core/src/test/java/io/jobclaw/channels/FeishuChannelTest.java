package io.jobclaw.channels;

import io.jobclaw.bus.InboundMessage;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.config.ChannelsConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FeishuChannelTest {

    @Test
    void handleIncomingMessagePublishesInboundMessage() throws Exception {
        MessageBus bus = new MessageBus();
        ChannelsConfig config = new ChannelsConfig();
        config.getFeishu().setEnabled(true);
        config.getFeishu().setAppId("app-id");
        config.getFeishu().setAppSecret("app-secret");

        FeishuChannel channel = new FeishuChannel(bus, config);

        channel.handleIncomingMessage("""
                {
                  "event": {
                    "sender": {
                      "sender_id": {
                        "user_id": "user-1"
                      },
                      "tenant_key": "tenant-1"
                    },
                    "message": {
                      "message_id": "msg-1",
                      "message_type": "text",
                      "chat_type": "group",
                      "chat_id": "chat-1",
                      "content": "{\\"text\\":\\"hello feishu\\"}"
                    }
                  }
                }
                """);

        InboundMessage inbound = bus.consumeInbound(1, TimeUnit.SECONDS);
        assertNotNull(inbound);
        assertEquals("feishu", inbound.getChannel());
        assertEquals("user-1", inbound.getSenderId());
        assertEquals("chat-1", inbound.getChatId());
        assertEquals("hello feishu", inbound.getContent());
        assertEquals("msg-1", inbound.getMetadata().get("message_id"));
        assertEquals("text", inbound.getMetadata().get("message_type"));
        assertEquals("group", inbound.getMetadata().get("chat_type"));
        assertEquals("tenant-1", inbound.getMetadata().get("tenant_key"));
    }

    @Test
    void startWebhookModeInitializesApiClient() throws Exception {
        MessageBus bus = new MessageBus();
        ChannelsConfig config = new ChannelsConfig();
        config.getFeishu().setEnabled(true);
        config.getFeishu().setAppId("app-id");
        config.getFeishu().setAppSecret("app-secret");
        config.getFeishu().setConnectionMode("webhook");

        FeishuChannel channel = new FeishuChannel(bus, config);
        channel.start();

        Field apiClientField = FeishuChannel.class.getDeclaredField("apiClient");
        apiClientField.setAccessible(true);
        Object apiClient = apiClientField.get(channel);

        assertNotNull(apiClient);
        assertTrue(channel.isConnected());

        channel.stop();
    }
}
