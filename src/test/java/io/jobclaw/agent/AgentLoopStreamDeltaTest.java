package io.jobclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentLoopStreamDeltaTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldKeepPureDeltaUnchanged() {
        assertEquals("world", AgentLoop.normalizeStreamDelta(new StringBuilder("hello "), "world"));
    }

    @Test
    void shouldConvertCumulativeChunkToDelta() {
        assertEquals("world", AgentLoop.normalizeStreamDelta(new StringBuilder("hello "), "hello world"));
    }

    @Test
    void shouldKeepCumulativeModeAfterItIsDetected() {
        AgentLoop.StreamDeltaNormalizer normalizer = new AgentLoop.StreamDeltaNormalizer();
        StringBuilder response = new StringBuilder();

        String first = normalizer.normalize(response, "hello");
        response.append(first);
        String second = normalizer.normalize(response, "hello world");
        response.append(second);

        assertEquals("hello world", response.toString());
    }

    @Test
    void shouldDetectCumulativeReasoningAfterDeltaToolRound() {
        AgentLoop.StreamDeltaNormalizer normalizer = new AgentLoop.StreamDeltaNormalizer();
        StringBuilder response = new StringBuilder();

        response.append(normalizer.normalize(response, "first tool round reasoning. "));
        response.append(normalizer.normalize(response, "Tool completed. "));
        response.append(normalizer.normalize(response, "The user"));
        response.append(normalizer.normalize(response, "The user wants to continue"));
        response.append(normalizer.normalize(response, "The user wants to continue writing."));

        assertEquals(
                "first tool round reasoning. Tool completed. The user wants to continue writing.",
                response.toString()
        );
    }

    @Test
    void shouldDetectLargeCumulativeSnapshotAfterSmallDeltas() {
        AgentLoop.StreamDeltaNormalizer normalizer = new AgentLoop.StreamDeltaNormalizer();
        StringBuilder response = new StringBuilder();

        response.append(normalizer.normalize(response, "Earlier reasoning. "));
        response.append(normalizer.normalize(response, "The user "));
        response.append(normalizer.normalize(response, "asked to "));
        response.append(normalizer.normalize(response, "continue. "));
        response.append(normalizer.normalize(response,
                "The user asked to continue. This is the next reasoning step after reading the files."));

        assertEquals(
                "Earlier reasoning. The user asked to continue. This is the next reasoning step after reading the files.",
                response.toString()
        );
    }

    @Test
    void shouldResetReasoningNormalizerAfterToolRound() {
        AgentLoop.ReasoningStreamState state = new AgentLoop.ReasoningStreamState("seg-test", 0);

        AgentLoop.NormalizedReasoning first = state.normalize(0, "Initial reasoning delta. ");
        AgentLoop.NormalizedReasoning second = state.normalize(0, "Call a tool now.");
        AgentLoop.NormalizedReasoning afterTool = state.normalize(1, "I need to investigate this");
        AgentLoop.NormalizedReasoning snapshot = state.normalize(1, "I need to investigate this further.");

        assertEquals("Initial reasoning delta. ", first.delta());
        assertEquals("Call a tool now.", second.delta());
        assertEquals("I need to investigate this", afterTool.delta());
        assertEquals(" further.", snapshot.delta());
        assertEquals(0, afterTool.accumulatedBefore());
        assertEquals(2, afterTool.roundIndex());
        assertEquals("seg-test-reasoning-2", afterTool.segmentId());
        assertEquals("I need to investigate this further.", state.currentRoundText());
    }

    @Test
    void shouldResetReasoningOnlyOnceForMultipleToolsInOneModelRound() {
        AgentLoop.ReasoningStreamState state = new AgentLoop.ReasoningStreamState("seg-test", 0);

        state.normalize(0, "Before tools.");
        AgentLoop.NormalizedReasoning afterTools = state.normalize(2, "After both tools.");
        AgentLoop.NormalizedReasoning sameRound = state.normalize(2, " Continue reasoning.");

        assertEquals(2, afterTools.roundIndex());
        assertEquals(2, sameRound.roundIndex());
        assertEquals("After both tools. Continue reasoning.", state.currentRoundText());
    }

    @Test
    void shouldNotSwitchDeltaModeToCumulativeMidStream() {
        AgentLoop.StreamDeltaNormalizer normalizer = new AgentLoop.StreamDeltaNormalizer();
        StringBuilder response = new StringBuilder();

        String first = normalizer.normalize(response, "## 四、示例\n\n如果任务是");
        response.append(first);
        String second = normalizer.normalize(response, "\"分析 10 个 Excel 文件并生成报告\"：");
        response.append(second);
        String third = normalizer.normalize(response, "## 四、示例\n\n如果任务是后续内容");

        assertEquals("## 四、示例\n\n如果任务是后续内容", third);
    }

    @Test
    void shouldNotTrimPartialOverlapBecauseNormalDeltasCanRepeatWords() {
        assertEquals(
                "本地环境已经有 `python-docx` 包，可以直接使用",
                AgentLoop.normalizeStreamDelta(
                        new StringBuilder("您的本地环境已经有 `"),
                        "本地环境已经有 `python-docx` 包，可以直接使用"
                )
        );
    }

    @Test
    void shouldNotInterleaveRepeatedMarkdownDelta() {
        assertEquals(
                "## 四、示例\n\n如果任务是\"分析 10 个 Excel 文件并生成报告\"：",
                AgentLoop.normalizeStreamDelta(
                        new StringBuilder("前文\n\n## 四、示例\n\n如果任务是"),
                        "## 四、示例\n\n如果任务是\"分析 10 个 Excel 文件并生成报告\"："
                )
        );
    }

    @Test
    void shouldDropExactDuplicateChunk() {
        assertEquals("", AgentLoop.normalizeStreamDelta(new StringBuilder("hello"), "hello"));
    }

    @Test
    void shouldSeparateOpenAiReasoningMetadataFromVisibleContent() {
        AssistantMessage message = AssistantMessage.builder()
                .content("正式回复")
                .properties(Map.of("reasoningContent", "思考过程"))
                .build();

        AgentLoop.StreamResponseParts parts = AgentLoop.extractStreamResponseParts(response(message));

        assertEquals("思考过程", parts.reasoning());
        assertEquals("正式回复", parts.content());
    }

    @Test
    void shouldSeparateDeepSeekReasoningFromVisibleContent() {
        DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder()
                .content("正式回复")
                .reasoningContent("DeepSeek 思考")
                .build();

        AgentLoop.StreamResponseParts parts = AgentLoop.extractStreamResponseParts(response(message));

        assertEquals("DeepSeek 思考", parts.reasoning());
        assertEquals("正式回复", parts.content());
    }

    @Test
    void shouldTreatThoughtChunksAsReasoningOnly() {
        AssistantMessage message = AssistantMessage.builder()
                .content("仅供思考")
                .properties(Map.of("isThought", true))
                .build();

        AgentLoop.StreamResponseParts parts = AgentLoop.extractStreamResponseParts(response(message));

        assertEquals("仅供思考", parts.reasoning());
        assertEquals("", parts.content());
    }

    @Test
    void shouldLeaveOrdinaryAssistantContentVisible() {
        AssistantMessage message = new AssistantMessage("普通回复");

        AgentLoop.StreamResponseParts parts = AgentLoop.extractStreamResponseParts(response(message));

        assertEquals("", parts.reasoning());
        assertEquals("普通回复", parts.content());
    }

    @Test
    void shouldExtractJsonObjectFromFencedModelReply() throws Exception {
        String extracted = AgentLoop.extractJsonObject("""
                提取结果如下：
                ```json
                {"题目":"示例","作者":"张三"}
                ```
                已完成。
                """);

        JsonNode node = MAPPER.readTree(extracted);
        assertEquals("示例", node.path("题目").asText());
        assertEquals("张三", node.path("作者").asText());
    }

    @Test
    void shouldRepairCommonJsonShapeErrors() throws Exception {
        String extracted = AgentLoop.extractJsonObject("""
                ```json
                {
                  “题目”: “示例”,
                  “主要内容”: “第一行
                第二行”,
                }
                ```
                """);

        JsonNode node = MAPPER.readTree(extracted);
        assertEquals("示例", node.path("题目").asText());
        assertEquals("第一行\n第二行", node.path("主要内容").asText());
        assertFalse(extracted.contains(",}"));
    }

    @Test
    void shouldExtractFirstBalancedJsonObjectWithoutUsingLastBrace() throws Exception {
        String extracted = AgentLoop.extractJsonObject("""
                前置说明 {不是JSON
                {"题目":"A","主要内容":"包含 } 字符"}
                后置说明 {"ignore":true}
                """);

        JsonNode node = MAPPER.readTree(extracted);
        assertEquals("A", node.path("题目").asText());
        assertEquals("包含 } 字符", node.path("主要内容").asText());
    }

    private ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }
}
