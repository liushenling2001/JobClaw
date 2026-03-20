package io.jobclaw.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.bus.OutboundMessage;
import io.jobclaw.config.ChannelsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * MaixCam Channel - Based on TCP Socket custom protocol
 *
 * Provides communication with MaixCam AI camera devices:
 * - TCP server listening for device connections
 * - Person detection event handling
 * - JSON protocol communication
 * - Multi-device support
 */
@Component
public class MaixCamChannel extends BaseChannel {

    private static final Logger logger = LoggerFactory.getLogger(MaixCamChannel.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ChannelsConfig.MaixCamConfig config;
    private ServerSocket serverSocket;
    private volatile boolean serverRunning = false;

    // Connected clients
    private final Map<Socket, PrintWriter> clients = new ConcurrentHashMap<>();

    public MaixCamChannel(MessageBus messageBus, ChannelsConfig config) {
        super(messageBus, config.getMaixcam().getAllowFrom());
        this.config = config.getMaixcam();
    }

    @Override
    public String getName() {
        return "maixcam";
    }

    @Override
    public void start() {
        logger.info("Starting MaixCam channel...");

        String host = config.getHost() != null ? config.getHost() : "0.0.0.0";
        int port = config.getPort() > 0 ? config.getPort() : 18790;

        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            throw new ChannelException("Failed to start MaixCam server", e);
        }

        serverRunning = true;
        running = true;

        logger.info("MaixCam server started on {}:{}", host, port);

        // Start connection acceptor thread
        Thread acceptThread = new Thread(this::acceptConnections, "maixcam-acceptor");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    @Override
    public void stop() {
        logger.info("Stopping MaixCam channel...");
        serverRunning = false;
        running = false;

        // Close all client connections
        for (Socket client : clients.keySet()) {
            try {
                client.close();
            } catch (IOException ignored) {}
        }
        clients.clear();

        // Close server socket
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }

        logger.info("MaixCam channel stopped");
    }

    @Override
    public void send(OutboundMessage message) {
        if (!running) {
            throw new IllegalStateException("MaixCam channel not running");
        }

        if (clients.isEmpty()) {
            logger.warn("No connected MaixCam devices");
            throw new ChannelException("No connected MaixCam devices");
        }

        // Build response message
        ObjectNode response = objectMapper.createObjectNode();
        response.put("type", "command");
        response.put("timestamp", System.currentTimeMillis() / 1000.0);
        response.put("message", message.getContent());
        response.put("chat_id", message.getChatId());

        String jsonMessage;
        try {
            jsonMessage = objectMapper.writeValueAsString(response);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ChannelException("Failed to serialize message", e);
        }

        // Send to all connected devices
        Exception lastError = null;
        for (Map.Entry<Socket, PrintWriter> entry : clients.entrySet()) {
            try {
                entry.getValue().println(jsonMessage);
            } catch (Exception e) {
                logger.error("Failed to send message to device: {}", entry.getKey().getRemoteSocketAddress());
                lastError = e;
            }
        }

        if (lastError != null) {
            throw new ChannelException("Failed to send message to device", lastError);
        }
    }

    private void acceptConnections() {
        while (serverRunning && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                logger.info("New MaixCam device connected: {}", client.getRemoteSocketAddress());

                clients.put(client, new PrintWriter(client.getOutputStream(), true));

                // Start handler thread
                Thread handlerThread = new Thread(() -> handleClient(client), "maixcam-handler");
                handlerThread.setDaemon(true);
                handlerThread.start();

            } catch (IOException e) {
                if (serverRunning) {
                    logger.error("Error accepting connection: {}", e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket client) {
        String clientAddr = client.getRemoteSocketAddress().toString();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()))) {
            String line;
            while (serverRunning && (line = reader.readLine()) != null) {
                processMessage(line, client);
            }
        } catch (IOException e) {
            logger.debug("Client disconnected: {}", clientAddr);
        } finally {
            clients.remove(client);
            try {
                client.close();
            } catch (IOException ignored) {}
        }
    }

    private void processMessage(String messageJson, Socket client) {
        try {
            JsonNode msg = objectMapper.readTree(messageJson);

            String type = msg.path("type").asText("");

            switch (type) {
                case "person_detected":
                    handlePersonDetection(msg);
                    break;
                case "heartbeat":
                    logger.debug("Received MaixCam heartbeat");
                    break;
                case "status":
                    handleStatusUpdate(msg);
                    break;
                default:
                    logger.warn("Unknown message type: {}", type);
            }
        } catch (Exception e) {
            logger.error("Error parsing message: {}", e.getMessage());
        }
    }

    private void handlePersonDetection(JsonNode msg) {
        String senderId = "maixcam";
        String chatId = "default";

        JsonNode data = msg.path("data");

        String className = data.path("class_name").asText("person");
        double score = data.path("score").asDouble(0);
        double x = data.path("x").asDouble(0);
        double y = data.path("y").asDouble(0);
        double w = data.path("w").asDouble(0);
        double h = data.path("h").asDouble(0);

        String content = String.format(
            "📷 Person detected!\nType: %s\nConfidence: %.2f%%\nPosition: (%.0f, %.0f)\nSize: %.0fx%.0f",
            className, score * 100, x, y, w, h
        );

        Map<String, String> metadata = new HashMap<>();
        metadata.put("timestamp", String.valueOf((long) msg.path("timestamp").asDouble(0)));
        metadata.put("class_name", className);
        metadata.put("score", String.format("%.2f", score));
        metadata.put("x", String.format("%.0f", x));
        metadata.put("y", String.format("%.0f", y));
        metadata.put("w", String.format("%.0f", w));
        metadata.put("h", String.format("%.0f", h));

        logger.info("Received person detection event: {}, confidence: {}", className, score);

        handleMessage(senderId, chatId, content, null, metadata);
    }

    private void handleStatusUpdate(JsonNode msg) {
        logger.info("Received MaixCam status update: {}", msg.path("data").toString());
    }
}
