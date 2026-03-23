package io.jobclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jobclaw.config.Config;
import io.jobclaw.config.ToolsConfig;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Web search tool using Baidu Qianfan Search API
 * 
 * API Key 配置优先级：
 * 1. config.json 中的 tools.web.search.api_key
 * 2. 环境变量 QIANFAN_API_KEY
 * 
 * 百度千帆搜索 API 文档：
 * https://cloud.baidu.com/doc/qianfan-api/s/Hmbu8m06u
 */
@Component
public class WebSearchTool {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String apiKey;
    private final int maxResults;
    private final OkHttpClient httpClient;

    public WebSearchTool(Config config) {
        // Try to get API key from config first, then environment
        String configApiKey = getApiKeyFromConfig(config);
        this.apiKey = configApiKey != null ? configApiKey : System.getenv("QIANFAN_API_KEY");
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

    @Tool(name = "web_search", description = "Search the web for current information using Baidu Qianfan Search API. Use this to find latest news, articles, and web content. Returns search results with titles, URLs, and snippets.")
    public String search(
        @ToolParam(description = "Search query") String query,
        @ToolParam(description = "Number of results (1-10, default: 5)") Integer count
    ) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "Error: QIANFAN_API_KEY not configured. Please set in config.json (tools.web.search.api_key) or environment variable.";
        }

        if (query == null || query.isEmpty()) {
            return "Error: query is required";
        }

        int resultCount = count != null && count > 0 && count <= 10 ? count : maxResults;

        try {
            // Use the correct API endpoint from documentation
            String url = "https://qianfan.baidubce.com/v2/ai_search/websearch";

            // Build request body according to documentation
            String requestBody = buildSearchRequestBody(query, resultCount);

            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(requestBody, JSON))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return "Error: Search failed with status " + response.code() + ": " + response.body().string();
                }

                String responseBody = response.body().string();
                return parseSearchResults(responseBody);
            }

        } catch (Exception e) {
            return "Error performing search: " + e.getMessage();
        }
    }

    /**
     * Build search request body according to Baidu Qianfan API documentation
     */
    private String buildSearchRequestBody(String query, int topK) throws Exception {
        // Use Map for simpler JSON building
        Map<String, Object> message = Map.of(
            "content", query,
            "role", "user"
        );

        Map<String, Object> resourceFilter = Map.of(
            "type", "web",
            "top_k", topK
        );

        Map<String, Object> requestBody = Map.of(
            "messages", List.of(message),
            "search_source", "baidu_search_v2",
            "resource_type_filter", List.of(resourceFilter)
        );

        return objectMapper.writeValueAsString(requestBody);
    }

    /**
     * Parse search results
     */
    private String parseSearchResults(String responseBody) throws Exception {
        JsonNode jsonNode = objectMapper.readTree(responseBody);
        
        StringBuilder result = new StringBuilder("🔍 **Search Results**\n\n");
        
        JsonNode results = jsonNode.get("data");
        if (results == null || !results.isArray()) {
            return "No results found.";
        }

        int count = 0;
        for (JsonNode item : results) {
            count++;
            String title = item.has("title") ? item.get("title").asText() : "No title";
            String url = item.has("url") ? item.get("url").asText() : "No URL";
            String snippet = item.has("content") ? item.get("content").asText() : "";

            result.append(count).append(". **").append(title).append("**\n");
            result.append("   URL: ").append(url).append("\n");
            if (!snippet.isEmpty()) {
                result.append("   ").append(snippet).append("\n");
            }
            result.append("\n");
        }

        return result.toString();
    }
}
