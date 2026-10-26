package io.jobclaw.agent;

import io.jobclaw.runtime.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactPathCompatibilityTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "/home/jobclaw/report.docx",
            "Saved: /tmp/report.csv.",
            "[report](/home/jobclaw/report.docx)",
            "`/home/jobclaw/My Reports/report.docx`",
            "\"/home/jobclaw/My Reports/report.docx\"",
            "'/home/jobclaw/My Reports/report.docx'",
            "{\"path\":\"/home/jobclaw/report.docx\"}",
            "D:\\work\\result.docx",
            "/report.md"
    })
    void detectsAbsoluteArtifactPaths(String text) {
        assertThat(AgentLoop.containsArtifactPath(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "Done", "https://example.com/reports/out.docx",
            "http://localhost:8000/v1/models", "//server/share/report.docx",
            "reports/result.docx", "./reports/result.docx", "../reports/result.docx",
            "~/reports/result.docx", "/", "1/2", "application/pdf",
            "[report](https://example.com/reports/out.docx)"
    })
    void doesNotTreatUrlsAndRelativePathsAsAbsoluteArtifacts(String text) {
        assertThat(AgentLoop.containsArtifactPath(text)).isFalse();
    }

    @Test
    void handlesChinesePathsAndSeparatesAdjacentPaths() {
        assertThat(AgentLoop.extractArtifactPaths("已保存：/home/用户/报告.docx，/tmp/汇总.csv。"))
                .containsExactly("/home/用户/报告.docx", "/tmp/汇总.csv");
        assertThat(AgentLoop.containsArtifactPath("资料/报告.docx")).isFalse();
    }

    @Test
    void preservesSpacesAndPunctuationInsideQuotedPaths() {
        assertThat(AgentLoop.extractArtifactPaths("`/home/user/My Reports/report (final).docx`"))
                .containsExactly("/home/user/My Reports/report (final).docx");
    }

    @Test
    void toolEvidenceUsesTheSamePathRecognitionAndDeduplicates() {
        var tracker = new AgentLoop.ArtifactCompletionTracker();
        tracker.recordToolResult("write_file", "{\"path\":\"/home/user/My Reports/report.docx\"}",
                new ToolExecutionResult("tool-1", "Saved `/home/user/My Reports/report.docx`", 1, true, null));
        assertThat(tracker.hasWriteEvidence()).isTrue();
        assertThat(tracker.artifactPaths()).containsExactly("/home/user/My Reports/report.docx");
    }

    @Test
    void retainsWindowsToolEvidence() {
        var tracker = new AgentLoop.ArtifactCompletionTracker();
        tracker.recordToolResult("write_file", "", new ToolExecutionResult(
                "tool-1", "Saved D:\\work\\report.docx", 1, true, null));
        assertThat(tracker.artifactPaths()).containsExactly("D:\\work\\report.docx");
    }
}
