package io.jobclaw.config;

import io.jobclaw.bus.MessageBus;
import io.jobclaw.channels.Channel;
import io.jobclaw.channels.ChannelManager;
import io.jobclaw.gateway.GatewayService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 网关服务 Bean 配置
 */
@Configuration
public class GatewayBeansConfig {

    /**
     * ChannelManager Bean
     * 注意：Channel 实例由 Spring 自动扫描并注入
     */
    @Bean
    public ChannelManager channelManager(MessageBus messageBus,
                                         ChannelsConfig channelsConfig,
                                         List<Channel> channelList) {
        return new ChannelManager(messageBus, channelsConfig, channelList);
    }

    /**
     * GatewayService Bean
     * 所有依赖通过 Spring 注入
     */
    @Bean
    public GatewayService gatewayService(Config config,
                                         MessageBus messageBus,
                                         io.jobclaw.cron.CronService cronService,
                                         ChannelManager channelManager,
                                         io.jobclaw.heartbeat.HeartbeatService heartbeatService) {
        return new GatewayService(
                config,
                messageBus,
                cronService,
                channelManager,
                heartbeatService
        );
    }
}
