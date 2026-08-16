package io.jobclaw.agent;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenAiCompatibleProviderReplayTest {

    private MockWebServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void shouldReplayVllmStyleTextStream() throws Exception {
        startServer();
        enqueueSse("harness/replays/provider/openai-compatible/text-stream.sse");

        List<String> chunks = client().prompt()
                .user("hello")
                .stream()
                .content()
                .collectList()
                .block(Duration.ofSeconds(10));

        assertNotNull(chunks);
        assertEquals("hello from vllm", normalize(chunks));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void shouldReplayStreamingToolCallAndFollowup() throws Exception {
        startServer();
        enqueueSse("harness/replays/provider/openai-compatible/tool-call.sse");
        enqueueSse("harness/replays/provider/openai-compatible/tool-followup.sse");
        AtomicInteger calls = new AtomicInteger();
        ToolCallback echo = new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name("echo_text")
                    .description("Echo text for replay testing")
                    .inputSchema("""
                            {
                              "type": "object",
                              "properties": {
                                "text": {"type": "string"}
                              },
                              "required": ["text"]
                            }
                            """)
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String request) {
                calls.incrementAndGet();
                return "echoed";
            }
        };

        List<String> chunks = client().prompt()
                .user("use the echo tool")
                .toolCallbacks(echo)
                .stream()
                .content()
                .collectList()
                .block(Duration.ofSeconds(10));

        assertNotNull(chunks);
        assertEquals("tool result accepted", normalize(chunks));
        assertEquals(1, calls.get());
        assertEquals(2, server.getRequestCount());
    }

    private void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    private ChatClient client() {
        String baseUrl = server.url("/v1").toString().replaceAll("/$", "");
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .apiKey("replay-key")
                .baseUrl(baseUrl)
                .model("replay-model")
                .timeout(Duration.ofSeconds(10))
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .options(options)
                .build();
        return ChatClient.builder(model).build();
    }

    private void enqueueSse(String path) throws Exception {
        String body;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing replay resource: " + path);
            }
            body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body));
    }

    private String normalize(List<String> chunks) {
        AgentLoop.StreamDeltaNormalizer normalizer = new AgentLoop.StreamDeltaNormalizer();
        StringBuilder accumulated = new StringBuilder();
        for (String chunk : chunks) {
            if (chunk != null && !chunk.isEmpty()) {
                accumulated.append(normalizer.normalize(accumulated, chunk));
            }
        }
        return accumulated.toString();
    }
}
