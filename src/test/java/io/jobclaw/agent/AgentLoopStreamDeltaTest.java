package io.jobclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
}
