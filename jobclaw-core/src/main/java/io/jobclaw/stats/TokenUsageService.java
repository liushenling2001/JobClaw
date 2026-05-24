package io.jobclaw.stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 使用统计服务
 * 
 * 跟踪 API 调用次数和 Token 消耗
 */
@Component
public class TokenUsageService {
    
    private static final Logger logger = LoggerFactory.getLogger(TokenUsageService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private static final String STATS_DIR = System.getProperty("user.home") + "/.jobclaw/stats";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    // 内存中的统计（按日期分组）
    private final Map<String, DailyStats> dailyStats = new ConcurrentHashMap<>();
    
    // 总计统计
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong(0);
    
    public TokenUsageService() {
        loadTodayStats();
    }
    
    /**
     * 记录 Token 使用
     */
    public void recordUsage(String model, int inputTokens, int outputTokens) {
        String today = LocalDate.now().format(DATE_FORMAT);
        
        DailyStats stats = dailyStats.computeIfAbsent(today, k -> new DailyStats());
        stats.requests++;
        stats.inputTokens += inputTokens;
        stats.outputTokens += outputTokens;
        stats.models.merge(model, 1L, Long::sum);
        
        totalRequests.incrementAndGet();
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
        
        logger.debug("Recorded token usage: model={}, input={}, output={}, total={}", 
                    model, inputTokens, outputTokens, inputTokens + outputTokens);
    }
    
    /**
     * 获取今日统计
     */
    public Map<String, Object> getTodayStats() {
        String today = LocalDate.now().format(DATE_FORMAT);
        DailyStats stats = dailyStats.get(today);
        
        if (stats == null) {
            return createEmptyStats("Today");
        }
        
        return createStatsMap("Today", stats);
    }
    
    /**
     * 获取总计统计
     */
    public Map<String, Object> getTotalStats() {
        Map<String, Object> result = new HashMap<>();
        result.put("period", "Total");
        result.put("requests", totalRequests.get());
        result.put("input_tokens", totalInputTokens.get());
        result.put("output_tokens", totalOutputTokens.get());
        result.put("total_tokens", totalInputTokens.get() + totalOutputTokens.get());
        
        // 估算成本（假设每 1000 tokens $0.002）
        double estimatedCost = (totalInputTokens.get() + totalOutputTokens.get()) / 1000.0 * 0.002;
        result.put("estimated_cost_usd", String.format("%.4f", estimatedCost));
        
        return result;
    }
    
    /**
     * 获取指定日期范围的统计
     */
    public Map<String, Object> getRangeStats(LocalDate startDate, LocalDate endDate) {
        long requests = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        
        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            String dateStr = current.format(DATE_FORMAT);
            DailyStats stats = dailyStats.get(dateStr);
            if (stats != null) {
                requests += stats.requests;
                inputTokens += stats.inputTokens;
                outputTokens += stats.outputTokens;
            }
            current = current.plusDays(1);
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("period", startDate + " to " + endDate);
        result.put("requests", requests);
        result.put("input_tokens", inputTokens);
        result.put("output_tokens", outputTokens);
        result.put("total_tokens", inputTokens + outputTokens);
        
        double estimatedCost = (inputTokens + outputTokens) / 1000.0 * 0.002;
        result.put("estimated_cost_usd", String.format("%.4f", estimatedCost));
        
        return result;
    }
    
    private Map<String, Object> createStatsMap(String period, DailyStats stats) {
        Map<String, Object> result = new HashMap<>();
        result.put("period", period);
        result.put("requests", stats.requests);
        result.put("input_tokens", stats.inputTokens);
        result.put("output_tokens", stats.outputTokens);
        result.put("total_tokens", stats.inputTokens + stats.outputTokens);
        
        double estimatedCost = (stats.inputTokens + stats.outputTokens) / 1000.0 * 0.002;
        result.put("estimated_cost_usd", String.format("%.4f", estimatedCost));
        
        return result;
    }
    
    private Map<String, Object> createEmptyStats(String period) {
        Map<String, Object> result = new HashMap<>();
        result.put("period", period);
        result.put("requests", 0);
        result.put("input_tokens", 0);
        result.put("output_tokens", 0);
        result.put("total_tokens", 0);
        result.put("estimated_cost_usd", "0.0000");
        return result;
    }
    
    private void loadTodayStats() {
        String today = LocalDate.now().format(DATE_FORMAT);
        Path statsFile = Paths.get(STATS_DIR, today + ".json");
        
        try {
            if (Files.exists(statsFile)) {
                String json = Files.readString(statsFile);
                DailyStats stats = objectMapper.readValue(json, DailyStats.class);
                dailyStats.put(today, stats);
                logger.info("Loaded stats for {}", today);
            }
        } catch (IOException e) {
            logger.warn("Failed to load stats, using empty", e);
        }
    }
    
    /**
     * 保存统计到文件
     */
    public void saveStats() {
        try {
            Path statsPath = Paths.get(STATS_DIR);
            Files.createDirectories(statsPath);
            
            for (Map.Entry<String, DailyStats> entry : dailyStats.entrySet()) {
                Path file = statsPath.resolve(entry.getKey() + ".json");
                String json = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(entry.getValue());
                Files.writeString(file, json);
            }
            
            logger.debug("Stats saved to {}", STATS_DIR);
        } catch (IOException e) {
            logger.error("Failed to save stats", e);
        }
    }
    
    /**
     * 每日统计数据结构
     */
    public static class DailyStats {
        public long requests = 0;
        public long inputTokens = 0;
        public long outputTokens = 0;
        public Map<String, Long> models = new ConcurrentHashMap<>();
        
        public DailyStats() {
        }
        
        // Getters and setters for Jackson
        public long getRequests() { return requests; }
        public void setRequests(long requests) { this.requests = requests; }
        public long getInputTokens() { return inputTokens; }
        public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }
        public long getOutputTokens() { return outputTokens; }
        public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }
        public Map<String, Long> getModels() { return models; }
        public void setModels(Map<String, Long> models) { this.models = models; }
    }
}
