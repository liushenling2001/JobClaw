package io.jobclaw.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Token 使用统计工具
 * 
 * 查询指定日期范围内的 token 消耗统计
 */
@Component
public class TokenUsageTool {

    @Tool(name = "query_token_usage", description = "Query token usage statistics for a date range. Use when user asks about token consumption, API costs, or model usage.")
    public String queryTokenUsage(
        @ToolParam(description = "Start date (yyyy-MM-dd format, e.g., 2024-01-01). Default: today") String start_date,
        @ToolParam(description = "End date (yyyy-MM-dd format, e.g., 2024-01-31). Default: today") String end_date
    ) {
        // TODO: Integrate with actual token usage tracking
        // For now, return placeholder response
        String startDate = (start_date != null && !start_date.isBlank()) ? start_date : java.time.LocalDate.now().toString();
        String endDate = (end_date != null && !end_date.isBlank()) ? end_date : java.time.LocalDate.now().toString();
        
        return "📊 Token Usage Statistics (" + startDate + " ~ " + endDate + ")\n\n" +
               "【Summary】\n" +
               "  Total Calls: 0\n" +
               "  Input Tokens: 0\n" +
               "  Output Tokens: 0\n" +
               "  Total Tokens: 0\n\n" +
               "(Token tracking not yet configured. This is a placeholder.)";
    }
}
