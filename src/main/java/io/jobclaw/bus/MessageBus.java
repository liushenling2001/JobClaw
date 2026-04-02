package io.jobclaw.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 消息总线 - 用于在通道和 Agent 之间路由消息
 */
@Component
public class MessageBus {

    private static final Logger logger = LoggerFactory.getLogger(MessageBus.class);

    private static final int DEFAULT_QUEUE_SIZE = 100;
    private static final int INBOUND_QUEUE_SIZE = Integer.getInteger(
            "jobclaw.bus.inbound.queue.size", DEFAULT_QUEUE_SIZE
    );
    private static final int OUTBOUND_QUEUE_SIZE = Integer.getInteger(
            "jobclaw.bus.outbound.queue.size", DEFAULT_QUEUE_SIZE
    );

    private final LinkedBlockingQueue<InboundMessage> inbound;
    private final LinkedBlockingQueue<OutboundMessage> outbound;
    private final Map<String, Function<InboundMessage, Void>> handlers;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong droppedInboundCount = new AtomicLong(0);
    private final AtomicLong droppedOutboundCount = new AtomicLong(0);

    public MessageBus() {
        this.inbound = new LinkedBlockingQueue<>(INBOUND_QUEUE_SIZE);
        this.outbound = new LinkedBlockingQueue<>(OUTBOUND_QUEUE_SIZE);
        this.handlers = new ConcurrentHashMap<>();

        logger.info("MessageBus initialized");
    }

    public void publishInbound(InboundMessage message) {
        if (closed.get()) {
            logger.warn("MessageBus is closed, rejecting inbound message");
            return;
        }
        if (!inbound.offer(message)) {
            long dropped = droppedInboundCount.incrementAndGet();
            logger.error("Inbound queue full, dropping message");
            return;
        }
        logger.debug("Published inbound message");
    }

    public InboundMessage consumeInbound() throws InterruptedException {
        while (!closed.get()) {
            InboundMessage message = inbound.poll(1, TimeUnit.SECONDS);
            if (message != null) {
                return message;
            }
        }
        return null;
    }

    public InboundMessage consumeInbound(long timeout, TimeUnit unit) throws InterruptedException {
        return inbound.poll(timeout, unit);
    }

    public void publishOutbound(OutboundMessage message) {
        if (closed.get()) {
            logger.warn("MessageBus is closed, rejecting outbound message");
            return;
        }
        if (!outbound.offer(message)) {
            long dropped = droppedOutboundCount.incrementAndGet();
            logger.error("Outbound queue full, dropping message");
            return;
        }
        logger.debug("Published outbound message");
    }

    public OutboundMessage subscribeOutbound() throws InterruptedException {
        while (!closed.get()) {
            OutboundMessage message = outbound.poll(1, TimeUnit.SECONDS);
            if (message != null) {
                return message;
            }
        }
        return null;
    }

    public OutboundMessage subscribeOutbound(long timeout, TimeUnit unit) throws InterruptedException {
        return outbound.poll(timeout, unit);
    }

    public void registerHandler(String channel, Function<InboundMessage, Void> handler) {
        handlers.put(channel, handler);
        logger.debug("Registered handler for channel: {}", channel);
    }

    public Function<InboundMessage, Void> getHandler(String channel) {
        return handlers.get(channel);
    }

    public boolean hasInbound() {
        return !inbound.isEmpty();
    }

    public int getInboundSize() {
        return inbound.size();
    }

    public int getOutboundSize() {
        return outbound.size();
    }

    public void clear() {
        inbound.clear();
        outbound.clear();
        logger.debug("Message bus cleared");
    }

    public void close() {
        closed.set(true);
        clear();
        logger.info("Message bus closed");
    }

    public boolean isClosed() {
        return closed.get();
    }

    public long getDroppedInboundCount() {
        return droppedInboundCount.get();
    }

    public long getDroppedOutboundCount() {
        return droppedOutboundCount.get();
    }
}
