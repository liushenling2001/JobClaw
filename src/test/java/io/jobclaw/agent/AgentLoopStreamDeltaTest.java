package io.jobclaw.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentLoopStreamDeltaTest {

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
}
