package io.jobclaw.channels;

import io.jobclaw.bus.MessageBus;
import io.jobclaw.bus.OutboundMessage;
import io.jobclaw.config.ChannelsConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelManagerStartReportTest {

    @Test
    void startAllHonorsEnabledFlagAndConfiguredState() {
        MessageBus bus = new MessageBus();
        ChannelsConfig config = new ChannelsConfig();
        config.getFeishu().setEnabled(true);
        config.getFeishu().setAppId("app-id");
        config.getFeishu().setAppSecret("app-secret");
        config.getTelegram().setEnabled(false);
        config.getDiscord().setEnabled(false);
        config.getMaixcam().setEnabled(false);

        Channel feishu = new TestChannel("feishu", true);
        Channel telegram = new TestChannel("telegram", true);
        Channel discord = new TestChannel("discord", true);
        Channel maixcam = new TestChannel("maixcam", true);

        ChannelManager manager = new ChannelManager(bus, config, List.of(feishu, telegram, discord, maixcam));
        manager.startAll();

        assertEquals(List.of("feishu"), manager.getLastStartReport().getStartedChannels());
        assertTrue(manager.getLastStartReport().getSkippedChannels().containsAll(List.of("telegram", "discord", "maixcam")));
        assertEquals(3, manager.getLastStartReport().getSkippedChannels().size());
        assertEquals(0, manager.getLastStartReport().getFailedChannels().size());

        manager.stopAll();
    }

    private static final class TestChannel extends BaseChannel {
        private final String name;
        private final boolean configured;

        private TestChannel(String name, boolean configured) {
            super(new MessageBus(), List.of());
            this.name = name;
            this.configured = configured;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void start() {
            setRunning(true);
        }

        @Override
        public void stop() {
            setRunning(false);
        }

        @Override
        public void send(OutboundMessage message) {
            // no-op
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }
    }
}
