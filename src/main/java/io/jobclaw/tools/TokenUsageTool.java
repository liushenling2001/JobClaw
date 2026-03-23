package io.jobclaw.tools;

import io.jobclaw.stats.TokenUsageService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Token 使用统计工具
 * 
 * 查询 API 调用次数和 Token 消耗统计
 * 
 * 支持的操作：
 * - today: 今日统计
 * - total: 总计统计
 * - range: 指定日期范围统计
 */
@Component
public class TokenUsageTool {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TokenUsageService tokenUsageService;

    public TokenUsageTool(TokenUsageService tokenUsageService) {
        this.tokenUsageService = tokenUsageService;
    }

    @Tool(name = "query_token_usage", description = "Query API usage statistics and token consumption. Use this when user asks about token usage, API costs, request counts, or wants to monitor their usage. Supports three modes: 'today' for today's stats, 'total' for lifetime stats, and 'range' for custom date ranges.")
    public String queryTokenUsage(
        @ToolParam(description = "Query type: today, total, or range") String type,
        @ToolParam(description = "Start date for range query (format: YYYY-MM-DD)") String start_date,
        @ToolParam(description = "End date for range query (format: YYYY-MM-DD)") String end_date
    ) {
        if (type == null || type.isEmpty()) {
            type = "today"; // Default to today's stats
        }

        return switch (type.toLowerCase()) {
            case "today" -> getTodayUsage();
            case "total" -> getTotalUsage();
            case "range" -> getRangeUsage(start_date, end_date);
            default -> "Error: Unknown query type '" + type + "'. Use 'today', 'total', or 'range'.";
        };
    }

    private String getTodayUsage() {
        try {
            Map<String, Object> stats = tokenUsageService.getTodayStats();
            
            StringBuilder result = new StringBuilder("📊 **Today's Token Usage** (" + LocalDate.now() + ")\n\n");
            result.append(formatStat(stats));
            
            return result.toString();
        } catch (Exception e) {
            return "Error fetching today's usage: " + e.getMessage();
        }
    }

    private String getTotalUsage() {
        try {
            Map<String, Object> stats = tokenUsageService.getTotalStats();
            
            StringBuilder result = new StringBuilder("📊 **Total Token Usage** (All Time)\n\n");
            result.append(formatStat(stats));
            
            return result.toString();
        } catch (Exception e) {
            return "Error fetching total usage: " + e.getMessage();
        }
    }

    private String getRangeUsage(String startDate, String endDate) {
        if (startDate == null || startDate.isEmpty() || endDate == null || endDate.isEmpty()) {
            return "Error: start_date and end_date are required for range query (format: YYYY-MM-DD)";
        }

        try {
            LocalDate start = LocalDate.parse(startDate, DATE_FORMAT);
            LocalDate end = LocalDate.parse(endDate, DATE_FORMAT);
            
            Map<String, Object> stats = tokenUsageService.getRangeStats(start, end);
            
            StringBuilder result = new StringBuilder("📊 **Token Usage** (" + startDate + " to " + endDate + ")\n\n");
            result.append(formatStat(stats));
            
            return result.toString();
        } catch (Exception e) {
            return "Error fetching range usage: " + e.getMessage();
        }
    }

    private String formatStat(Map<String, Object> stats) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("- **Requests**: ").append(stats.get("requests")).append("\n");
        sb.append("- **Input Tokens**: ").append(stats.get("input_tokens")).append("\n");
        sb.append("- **Output Tokens**: ").append(stats.get("output_tokens")).append("\n");
        sb.append("- **Total Tokens**: ").append(stats.get("total_tokens")).append("\n");
        sb.append("- **Estimated Cost**: $").append(stats.get("estimated_cost_usd")).append("\n");
        
        return sb.toString();
    }
}
