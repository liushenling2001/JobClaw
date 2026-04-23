package io.jobclaw.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSelectionPolicyTest {

    private final ToolSelectionPolicy policy = new ToolSelectionPolicy();

    @Test
    void shouldNotSelectWorklistToolsFromPromptTextAlone() {
        Set<String> selected = policy.selectToolNames(
                "批量审查目录 D:\\DOC 下的所有 PDF 文件，每个文件独立作为子任务处理",
                allTools()
        );

        assertFalse(selected.contains("subtasks"));
        assertFalse(selected.contains("spawn"));
        assertTrue(selected.contains("list_dir"));
        assertTrue(selected.contains("read_pdf"));
        assertTrue(selected.contains("memory"));
        assertTrue(selected.contains("skills"));
    }

    @Test
    void shouldSelectDocumentReadersForDocumentSummaryTasks() {
        Set<String> selected = policy.selectToolNames(
                "总结 xwd2014.xlsx 文件内容并分析关键数据",
                allTools()
        );

        assertTrue(selected.contains("read_excel"));
        assertTrue(selected.contains("read_pdf"));
        assertTrue(selected.contains("read_word"));
        assertFalse(selected.contains("subtasks"));
    }

    @Test
    void shouldSelectWriteAndExecutionToolsForReportArtifacts() {
        Set<String> selected = policy.selectToolNames(
                "先读取 Excel 数据，然后生成报告并保存到工作区",
                allTools()
        );

        assertTrue(selected.contains("read_excel"));
        assertTrue(selected.contains("write_file"));
        assertTrue(selected.contains("run_command"));
    }

    @Test
    void shouldKeepSimpleAnswerToolsetSmallButKeepCoreExecutionTools() {
        Set<String> selected = policy.selectToolNames(
                "解释一下什么是上下文压缩",
                allTools()
        );

        assertTrue(selected.contains("memory"));
        assertTrue(selected.contains("skills"));
        assertFalse(selected.contains("read_pdf"));
        assertFalse(selected.contains("subtasks"));
        assertTrue(selected.contains("run_command"));
        assertTrue(selected.contains("exec"));
        assertTrue(selected.contains("write_file"));
    }

    @Test
    void shouldAddSpecialToolsOnlyWhenRequested() {
        Set<String> selected = policy.selectToolNames(
                "明天上午提醒我检查 token 用量，并通过飞书发送消息",
                allTools()
        );

        assertTrue(selected.contains("cron"));
        assertTrue(selected.contains("query_token_usage"));
        assertTrue(selected.contains("message"));
    }

    @Test
    void shouldMergeRuntimeRequiredToolsIntoSelectedToolset() {
        Set<String> selected = policy.selectToolNames(
                "批量总结目录中的 PDF 文件",
                allTools(),
                Set.of("write_file", "edit_file", "append_file")
        );

        assertFalse(selected.contains("subtasks"));
        assertFalse(selected.contains("spawn"));
        assertTrue(selected.contains("write_file"));
        assertTrue(selected.contains("edit_file"));
        assertTrue(selected.contains("append_file"));
    }

    private List<String> allTools() {
        return List.of(
                "memory",
                "skills",
                "list_dir",
                "read_file",
                "read_pdf",
                "read_word",
                "read_excel",
                "write_file",
                "edit_file",
                "append_file",
                "run_command",
                "exec",
                "subtasks",
                "spawn",
                "agent_catalog",
                "collaborate",
                "board_write",
                "board_read",
                "cron",
                "message",
                "query_token_usage",
                "mcp",
                "web_search",
                "web_fetch"
        );
    }
}
