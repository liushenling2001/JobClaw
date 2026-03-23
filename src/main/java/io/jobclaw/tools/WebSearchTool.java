package io.jobclaw.tools;

import io.jobclaw.config.Config;
import io.jobclaw.config.ToolsConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Web search tool using Brave Search API
 * 
 * API Key 配置优先级：
 * 1. config.json 中的 tools.web_search.api_key
 * 2. 环境变量 BRAVE_API_KEY
 */
@Component
public class WebSearchTool {

    private final String apiKey;
    private final int maxResults;
    private final OkHttpClient httpClient;

    public WebSearchTool(Config config) {
        // Try to get API key from config first, then environment
        String configApiKey = getApiKeyFromConfig(config);
        this.apiKey = configApiKey != null ? configApiKey : System.getenv("BRAVE_API_KEY");
        this.maxResults = 5;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Extract API key from config
     */
    private String getApiKeyFromConfig(Config config) {
        try {
            ToolsConfig toolsConfig = config.getTools();
            if (toolsConfig != null && toolsConfig.getWeb() != null) {
                String apiKey = toolsConfig.getWeb().getSearch().getApiKey();
                if (apiKey != null && !apiKey.isEmpty()) {
                    return apiKey;
                }
            }
        } catch (Exception e) {
            // Ignore config errors, will fallback to environment
        }
        return null;
    }

    @Tool(name = "web_search", description = "Search the web for current information using Brave Search API")
    public String search(
        @ToolParam(description = "Search query") String query,
        @ToolParam(description = "Number of results (1-10, default: 5)") Integer count
    ) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "Error: BRAVE_API_KEY not configured. Please set the environment variable.";
        }

        if (query == null || query.isEmpty()) {
            return "Error: query is required";
        }

        int resultCount = count != null && count > 0 && count <= 10 ? count : maxResults;

        String url = String.format(
                "https://api.search.brave.com/res/v1/web/search?q=%s&count=%d",
                URLEncoder.encode(query, StandardCharsets.UTF_8),
                resultCount
        );

        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return "Error: Search API returned status " + response.code();
            }

            String body = response.body() != null ? response.body().string() : "{}";
            
            // Simple JSON parsing (you can use Jackson for better parsing)
            StringBuilder result = new StringBuilder();
            result.append("Search results for: ").append(query).append("\n\n");

            // Extract results from JSON (simplified parsing)
            int resultNum = 1;
            String[] lines = body.split("\"results\":\\s*\\[");
            if (lines.length > 1) {
                String resultsPart = lines[1];
                String[] items = resultsPart.split("\\},\\s*\\{");
                
                for (String item : items) {
                    if (resultNum > resultCount) break;
                    
                    // Extract title
                    String title = extractJsonValue(item, "\"title\"");
                    String itemUrl = extractJsonValue(item, "\"url\"");
                    String description = extractJsonValue(item, "\"description\"");

                    result.append(resultNum).append(". ").append(title).append("\n");
                    result.append("   ").append(itemUrl).append("\n");
                    if (!description.isEmpty()) {
                        result.append("   ").append(description).append("\n");
                    }
                    result.append("\n");
                    resultNum++;
                }
            }

            return result.toString();
        } catch (Exception e) {
            return "Search failed: " + e.getMessage();
        }
    }

    /**
     * Extract JSON value by key (simplified parser)
     */
    private String extractJsonValue(String json, String key) {
        String searchKey = key + "\":\"";
        int startIndex = json.indexOf(searchKey);
        if (startIndex == -1) {
            return "";
        }
        startIndex += searchKey.length();
        int endIndex = json.indexOf("\"", startIndex);
        if (endIndex == -1) {
            return "";
        }
        return json.substring(startIndex, endIndex);
    }
}
