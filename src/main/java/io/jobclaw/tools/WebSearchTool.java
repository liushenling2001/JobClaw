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
    private final String secretKey;
    private final int maxResults;
    private final OkHttpClient httpClient;

    public WebSearchTool(Config config) {
        // Try to get API keys from config first, then environment
        String configApiKey = getApiKeyFromConfig(config);
        String configSecretKey = getSecretKeyFromConfig(config);
        
        this.apiKey = configApiKey != null ? configApiKey : System.getenv("QIANFAN_API_KEY");
        this.secretKey = configSecretKey != null ? configSecretKey : System.getenv("QIANFAN_SECRET_KEY");
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

    /**
     * Extract Secret key from config
     */
    private String getSecretKeyFromConfig(Config config) {
        try {
            ToolsConfig toolsConfig = config.getTools();
            if (toolsConfig != null && toolsConfig.getWeb() != null) {
                String secretKey = toolsConfig.getWeb().getSearch().getSecretKey();
                if (secretKey != null && !secretKey.isEmpty()) {
                    return secretKey;
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

        if (secretKey == null || secretKey.isEmpty()) {
            return "Error: QIANFAN_SECRET_KEY not configured. Please set in config.json (tools.web.search.secret_key) or environment variable.";
        }

        if (query == null || query.isEmpty()) {
            return "Error: query is required";
        }

        int resultCount = count != null && count > 0 && count <= 10 ? count : maxResults;

        try {
            // Step 1: Get access token
            String accessToken = getAccessToken();
            
            // Step 2: Search with access token
            String url = String.format(
                    "https://qianfan.baidubce.com/api/v1/search?q=%s&top=%d",
                    URLEncoder.encode(query, StandardCharsets.UTF_8),
                    resultCount
            );

            Request request = new Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
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
     * Get access token from Baidu OAuth
     */
    private String getAccessToken() throws Exception {
        String oauthUrl = "https://qianfan.baidubce.com/oauth/2.0/token";
        
        String requestBody = String.format(
                "grant_type=client_credentials&client_id=%s&client_secret=%s",
                apiKey,
                secretKey
        );

        Request request = new Request.Builder()
                .url(oauthUrl)
                .post(RequestBody.create(requestBody, MediaType.parse("application/x-www-form-urlencoded")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Failed to get access token: " + response.code());
            }

            String responseBody = response.body().string();
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            
            if (jsonNode.has("access_token")) {
                return jsonNode.get("access_token").asText();
            } else {
                throw new Exception("No access_token in response");
            }
        }
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
