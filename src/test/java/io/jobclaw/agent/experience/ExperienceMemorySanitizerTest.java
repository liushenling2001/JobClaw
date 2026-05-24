package io.jobclaw.agent.experience;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceMemorySanitizerTest {

    @Test
    void shouldRemoveStatefulExecutionTargetsFromExperienceText() {
        ExperienceMemorySanitizer.SanitizedText sanitized = ExperienceMemorySanitizer.sanitize(
                "清理文件夹 D:\\old\\input，继续使用 manifestId=old-mf，artifactPath=D:\\old\\result.xlsx pending=3 done=7"
        );

        assertTrue(sanitized.sanitized());
        assertFalse(sanitized.text().contains("D:\\old\\input"));
        assertFalse(sanitized.text().contains("old-mf"));
        assertFalse(sanitized.text().contains("D:\\old\\result.xlsx"));
        assertFalse(sanitized.text().contains("pending=3"));
        assertTrue(sanitized.text().contains("current"));
    }
}
