package io.jobclaw.channels;

import io.jobclaw.bus.InboundMessage;
import io.jobclaw.bus.OutboundMessage;

public interface Channel {

    String getName();

    void start() throws Exception;

    void stop() throws Exception;

    void send(OutboundMessage message) throws Exception;

    boolean isAllowed(String senderId);

    boolean isConnected();

    default boolean isConfigured() {
        return true;
    }
}
