package io.jobclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jobclaw.agent.turn.TurnExecutionLedger;
import io.jobclaw.agent.turn.FinalizationNudgeGuard;
import io.jobclaw.config.Config;
import io.jobclaw.context.ContextAssembler;
import io.jobclaw.context.ContextAssemblyPolicy;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.SummaryService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import io.jobclaw.agent.completion.CompletionRegistry;
import io.jobclaw.agent.completion.ActiveExecutionRegistry;
import io.jobclaw.runtime.provider.ProviderRuntime;
import io.jobclaw.context.result.NoopResultStore;
import java.nio.file.Path;
import java.nio.file.Files;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class AgentLoopTurnResponseReplayTest {
    @TempDir
    Path workspace;
    private final MockWebServer server = new MockWebServer();
    private final ObjectMapper mapper = new ObjectMapper();
    private final TurnExecutionLedger ledger = new TurnExecutionLedger("replay", "run");
    private final List<String> visible = new CopyOnWriteArrayList<>();
    private final StringBuilder saved = new StringBuilder();

    @Test
    void artifactAndPromiseShareOneFollowupRequest() throws Exception {
        server.start();
        server.enqueue(sse(chunk("让我先创建文件。", "stop") + "data: [DONE]\n\n"));
        server.enqueue(sse(chunk("我接下来会写入文件。", "stop") + "data: [DONE]\n\n"));
        // A sentinel makes accidental extra requests fail assertions instead of hanging.
        server.enqueue(sse(chunk("不应再次调用。", "stop") + "data: [DONE]\n\n"));
        assertEquals("我接下来会写入文件。", runFullTurn("生成报告文件"));
        assertEquals(2, server.getRequestCount());
        server.takeRequest();
        String request = server.takeRequest().getBody().readUtf8();
        assertTrue(request.contains("JOBCLAW_ARTIFACT_COMPLETION_GUARD"));
        assertTrue(request.contains("JOBCLAW_PROMISE_CONTINUATION"));
    }

    @Test
    void passedArtifactCheckStopsEvenWhenFinalTextIsAPromise() throws Exception {
        server.start();
        Path artifact = Files.writeString(workspace.resolve("report.txt"), "complete");
        server.enqueue(sse(toolFixture()));
        server.enqueue(sse(chunk("让我先创建文件。", "stop") + "data: [DONE]\n\n"));
        server.enqueue(sse(chunk("不应补催。", "stop") + "data: [DONE]\n\n"));
        server.enqueue(sse(chunk("不应补催。", "stop") + "data: [DONE]\n\n"));
        assertEquals("让我先创建文件。", runFullTurn("生成报告文件", artifact));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void completionRegisteredDuringNudgeTakesOverFinalization() throws Exception {
        server.start();
        Path artifact = Files.writeString(workspace.resolve("report.txt"), "complete");
        server.enqueue(sse(chunk("让我先创建文件。", "stop") + "data: [DONE]\n\n"));
        server.enqueue(sse(toolFixture()));
        server.enqueue(sse(chunk("我接下来会读取文件。", "stop") + "data: [DONE]\n\n"));
        server.enqueue(sse(chunk("不应补催。", "stop") + "data: [DONE]\n\n"));
        assertEquals("我接下来会读取文件。", runFullTurn("生成报告文件", artifact));
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void failedRegisteredCheckUsesOnlyExistingCheckRecovery() throws Exception {
        server.start();
        server.enqueue(sse(toolFixture()));
        server.enqueue(sse(chunk("让我先创建文件。", "stop") + "data: [DONE]\n\n"));
        server.enqueue(sse(chunk("让我先创建文件。", "stop") + "data: [DONE]\n\n"));
        server.enqueue(sse(chunk("不应补催。", "stop") + "data: [DONE]\n\n"));
        assertTrue(runFullTurn("生成报告文件", workspace.resolve("missing.txt"))
                .startsWith("Error: completion checks failed"));
        assertEquals(3, server.getRequestCount());
        server.takeRequest();
        server.takeRequest();
        String recovery = server.takeRequest().getBody().readUtf8();
        assertTrue(recovery.contains("Completion check failed"));
        assertFalse(recovery.contains("JOBCLAW_FINALIZATION_NUDGE"));
    }

    @Test
    void fullLoopGreetingDoesNotRequestASecondAnswer() throws Exception {
        server.start();
        server.enqueue(sse(chunk("你好！", "stop") + "data: [DONE]\n\n"));
        assertEquals("你好！", runFullTurn("你好"));
        assertEquals(1, server.getRequestCount());
        String request = server.takeRequest().getBody().readUtf8();
        assertFalse(request.contains("jobclaw_turn"));
        assertFalse(request.contains("turn_control"));
    }

    @Test
    void fullLoopOnlyNudgesOnceWhenModelKeepsPromising() throws Exception {
        server.start();
        server.enqueue(sse(chunk("让我先检查目录。", "stop") + "data: [DONE]\n\n"));
        server.enqueue(sse(chunk("我接下来会读取文件。", "stop") + "data: [DONE]\n\n"));
        assertEquals("我接下来会读取文件。", runFullTurn("检查目录"));
        assertEquals(2, server.getRequestCount());
        server.takeRequest();
        assertTrue(server.takeRequest().getBody().readUtf8().contains("JOBCLAW_PROMISE_CONTINUATION"));
    }

    @Test
    void fullLoopDoesNotNudgeAPromiseFollowedByToolExecution() throws Exception {
        server.start();
        server.enqueue(sse(chunk("让我先检查目录。", null) + toolFixture()));
        server.enqueue(sse(chunk("检查结束，没有异常。", "stop") + "data: [DONE]\n\n"));
        assertTrue(runFullTurn("检查目录").endsWith("检查结束，没有异常。"));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void fullLoopCanNudgeANewPromiseAfterEarlierTools() throws Exception {
        server.start();
        server.enqueue(sse(toolFixture()));
        server.enqueue(sse(chunk("我接下来会读取文件。", "stop") + "data: [DONE]\n\n"));
        server.enqueue(sse(chunk("检查结束。", "stop") + "data: [DONE]\n\n"));
        assertEquals("检查结束。", runFullTurn("检查目录"));
        assertEquals(3, server.getRequestCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"继续查看下一个目录", "接下来查看剩余目录。", "接着核对文件清单。", "我再读取第二个文件。"})
    void continuesAfterBarePromiseAtTheEndOfAToolRound(String promise) throws Exception {
        server.start();
        server.enqueue(sse(toolFixture()));
        server.enqueue(sse(chunk(promise.substring(0, 3), null) + chunk(promise.substring(3), "stop") + "data: [DONE]\n\n"));
        server.enqueue(sse(toolFixture()));
        server.enqueue(sse(chunk("检查结束，没有异常。", "stop") + "data: [DONE]\n\n"));
        assertEquals("检查结束，没有异常。", runFullTurn("检查目录"));
        assertEquals(4, server.getRequestCount());
        server.takeRequest();
        server.takeRequest();
        String recovery = server.takeRequest().getBody().readUtf8();
        assertTrue(recovery.contains("JOBCLAW_PROMISE_CONTINUATION"));
        assertFalse(recovery.contains("JOBCLAW_ARTIFACT_COMPLETION_GUARD"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"继续查看下一个目录。", "下一步读取配置。", "我马上检查文件。"})
    void barePromiseWithAFollowingToolDoesNotNudge(String promise) throws Exception {
        server.start();
        server.enqueue(sse(chunk(promise, null) + toolFixture()));
        server.enqueue(sse(chunk("检查结束，没有异常。", "stop") + "data: [DONE]\n\n"));
        assertTrue(runFullTurn("检查目录").endsWith("检查结束，没有异常。"));
        assertEquals(2, server.getRequestCount());
    }

    private String toolFixture() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("harness/replays/provider/openai-compatible/tool-call.sse")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String runFullTurn(String userText) {
        return runFullTurn(userText, null);
    }

    private String runFullTurn(String userText, Path artifact) {
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(workspace.toString());
        config.getAgent().setProvider("openai");
        config.getAgent().setModel("replay-model");
        config.getProviders().getOpenai().setApiKey("test");
        config.getProviders().getOpenai().setApiBase(server.url("/v1").toString());
        config.getProviders().getOpenai().setStreaming(true);
        ContextBuilder context = mock(ContextBuilder.class);
        when(context.buildSystemPrompt(anyString(), anyString())).thenReturn("You are a test assistant.");
        CompletionRegistry completion = new CompletionRegistry(config);
        ToolCallback echo = new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("echo_text").description("echo")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}").build();
            }
            public String call(String input) {
                if (artifact != null) {
                    try {
                        String checks = mapper.writeValueAsString(List.of(Map.of(
                                "type", "file_non_empty", "path", artifact.toString())));
                        completion.register("full-replay", AgentExecutionContext.getCurrentRunId(), checks, "", 2);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
                return "echoed";
            }
        };
        AgentLoop loop = new AgentLoop(config, mock(SessionManager.class), new ToolCallback[]{echo}, context,
                (session, message, options) -> List.of(),
                (session, message) -> new io.jobclaw.context.ContextAssemblyOptions(0, 0, 0, 0, 0),
                mock(SummaryService.class), null, null, new ProviderRuntime(),
                new ActiveExecutionRegistry(), new NoopResultStore(), completion);
        var definition = AgentDefinition.builder().code("replay").displayName("Replay")
                .allowedTool("echo_text").build();
        return loop.processWithDefinition("full-replay", userText, definition, this::collect);
    }

    @AfterEach
    void close() throws Exception {
        server.shutdown();
    }

    @Test
    void streamsImmediatelyWithoutAControlMarkerAndFinishesWithOneRequest() throws Exception {
        server.start();
        String first = chunk("hello ", null);
        String rest = chunk("world", "stop")
                + "data: [DONE]\n\n";
        server.enqueue(sse(first + rest).throttleBody(first.getBytes(StandardCharsets.UTF_8).length, 200, TimeUnit.MILLISECONDS));
        CountDownLatch arrived = new CountDownLatch(1);
        var pending = CompletableFuture.supplyAsync(() -> {
            try {
                return invoke(true, new ToolCallback[0], event -> {
                    collect(event);
                    if (!visible.isEmpty()) arrived.countDown();
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        try {
            assertTrue(arrived.await(10, TimeUnit.SECONDS));
            assertFalse(pending.isDone(), "Body must arrive before the stream finishes");
        } finally {
            pending.get(15, TimeUnit.SECONDS);
        }
        assertEquals("hello world", pending.join());
        assertEquals("hello world", String.join("", visible));
        assertEquals("hello world", saved.toString());
        assertEquals("", new FinalizationNudgeGuard().continuationPrompt(io.jobclaw.agent.completion.CompletionGateResult.unregistered(), "", ledger.lastAssistantSegment(), false, false));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void keepsSpringAiToolContinuationWithoutAControlMarker() throws Exception {
        server.start();
        try (var resource = getClass().getClassLoader().getResourceAsStream("harness/replays/provider/openai-compatible/tool-call.sse")) {
            assertNotNull(resource);
            server.enqueue(sse(new String(resource.readAllBytes(), StandardCharsets.UTF_8)));
        }
        server.enqueue(sse(chunk("finished", "stop")
                + "data: [DONE]\n\n"));
        ToolCallback echo = new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("echo_text").description("echo")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}").build();
            }
            public String call(String input) { return "echoed"; }
        };
        assertEquals("finished", invoke(true, new ToolCallback[]{echo}, this::collect));
        assertEquals("finished", String.join("", visible));
        assertEquals("finished", ledger.lastAssistantSegment());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void nonStreamingConfigurationDoesNotRequireAMarker() throws Exception {
        server.start();
        String body = mapper.writeValueAsString(Map.of("id", "reply", "object", "chat.completion", "created", 1,
                "model", "replay-model", "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                        "message", Map.of("role", "assistant", "content", "answer")))));
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body));
        assertEquals("answer", invoke(false, new ToolCallback[0], this::collect));
        assertEquals("answer", String.join("", visible));
        assertEquals("answer", saved.toString());
        assertEquals("answer", ledger.lastAssistantSegment());
        assertEquals(1, server.getRequestCount());
    }

    private void collect(ExecutionEvent event) {
        if (event.getType() == ExecutionEvent.EventType.THINK_STREAM
                && !Boolean.TRUE.equals(event.getMetadata().get("reasoning"))) {
            visible.add(event.getContent());
        }
    }

    private String invoke(boolean streaming, ToolCallback[] tools, Consumer<ExecutionEvent> consumer) throws Exception {
        Config config = Config.defaultConfig();
        config.getAgent().setProvider("ollama");
        config.getAgent().setModel("llama3.1");
        AgentLoop loop = new AgentLoop(config, mock(SessionManager.class), tools, mock(ContextBuilder.class),
                mock(ContextAssembler.class), mock(ContextAssemblyPolicy.class), mock(SummaryService.class));
        String base = server.url("/v1").toString();
        var options = OpenAiChatOptions.builder().apiKey("test").baseUrl(base).model("replay-model").timeout(Duration.ofSeconds(10));
        var client = ChatClient.builder(OpenAiChatModel.builder().options(options.build()).build()).build();
        Class<?> bundleType = Arrays.stream(AgentLoop.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("ExecutionClientBundle")).findFirst().orElseThrow();
        var ctor = bundleType.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object bundle = ctor.newInstance(client, "replay-model", "openai", base, streaming);
        Method method = AgentLoop.class.getDeclaredMethod("runModelAttempt", bundleType, List.class,
                ToolCallback[].class, ChatOptions.Builder.class, String.class, Consumer.class, StringBuilder.class,
                boolean.class, TurnExecutionLedger.class);
        method.setAccessible(true);
        return (String) method.invoke(loop, bundle, List.of(new UserMessage("hello")), tools, options,
                "replay", consumer, saved, false, ledger);
    }

    private String chunk(String text, String finish) throws Exception {
        var choice = new java.util.LinkedHashMap<String, Object>();
        choice.put("index", 0);
        choice.put("delta", Map.of("content", text));
        choice.put("finish_reason", finish);
        return "data: " + mapper.writeValueAsString(Map.of("id", "reply", "object", "chat.completion.chunk",
                "created", 1, "model", "replay-model", "choices", List.of(choice))) + "\n\n";
    }

    private MockResponse sse(String body) {
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body);
    }
}
