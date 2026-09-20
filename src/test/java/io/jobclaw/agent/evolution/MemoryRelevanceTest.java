package io.jobclaw.agent.evolution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRelevanceTest {
    @Test
    void chineseInterveningWordsDoNotPreventLiteralRelevance() {
        assertTrue(MemoryRelevance.score("清理这个文件夹", "清理文件夹前确认目标") > 0);
        assertTrue(MemoryRelevance.score("检查论文格式", "论文格式检查规范") > 0);
    }

    @Test
    void greetingsGenericNounsAndEnglishStopWordsCannotFillMemory() {
        assertEquals(0, MemoryRelevance.score("你好", "清理文件夹的经验"));
        assertEquals(0, MemoryRelevance.score("文件夹的图标颜色", "清理文件夹前确认目标"));
        assertEquals(0, MemoryRelevance.score("please do this", "please verify that directory"));
        assertEquals(0, MemoryRelevance.score("API", "rapid file transfer"));
        assertTrue(MemoryRelevance.score("检查 PDF", "PDF redaction") > 0);
    }
}
