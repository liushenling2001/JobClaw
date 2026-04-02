package io.jobclaw.tools;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Web page content fetcher
 * Fetches URL and extracts readable text (HTML to text)
 */
@Component
public class WebFetchTool {

    private static final String USER_AGENT = "Mozilla/5.0 (compatible; JobClaw/1.0)";
    private static final int DEFAULT_MAX_CHARS = 50000;

    private final int maxChars;
    private final OkHttpClient httpClient;

    // Patterns for HTML cleanup
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("<script[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_PATTERN = Pattern.compile("<style[\\s\\S]*?</style>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    public WebFetchTool() {
        this.maxChars = DEFAULT_MAX_CHARS;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    @Tool(name = "web_fetch", description = "Fetch a URL and extract readable content (HTML to text). Useful for getting weather, news, articles, or any web page content.")
    public String fetch(
        @ToolParam(description = "The URL to fetch (http/https only)") String url,
        @ToolParam(description = "Maximum characters to extract (optional, default: 50000)") Integer maxChars
    ) {
        if (url == null || url.isEmpty()) {
            return "Error: url is required";
        }

        // Validate URL
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return "Error: Invalid URL: " + e.getMessage();
        }

        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "Error: Only http/https URLs are allowed";
        }

        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            return "Error: URL must have a hostname";
        }

        int max = maxChars != null && maxChars > 100 ? maxChars : maxChars;

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return "Error: HTTP " + response.code();
            }

            String contentType = response.header("Content-Type", "");
            String body = response.body() != null ? response.body().string() : "";

            String text;
            String extractor;

            if (contentType.contains("application/json")) {
                // JSON content - return as-is (pretty print would be better)
                text = body;
                extractor = "json";
            } else if (contentType.contains("text/html") || 
                       body.trim().startsWith("<!DOCTYPE") || 
                       body.trim().toLowerCase().startsWith("<html")) {
                // HTML content - extract text
                text = extractText(body);
                extractor = "text";
            } else {
                // Other content - return raw
                text = body;
                extractor = "raw";
            }

            // Truncate if needed
            boolean truncated = text.length() > max;
            if (truncated) {
                text = text.substring(0, max);
            }

            // Build result
            StringBuilder result = new StringBuilder();
            result.append("URL: ").append(url).append("\n");
            result.append("Status: ").append(response.code()).append("\n");
            result.append("Extractor: ").append(extractor).append("\n");
            if (truncated) {
                result.append("Truncated: yes (max ").append(max).append(" chars)\n");
            }
            result.append("Length: ").append(text.length()).append("\n\n");
            result.append("--- Content ---\n\n");
            result.append(text);

            return result.toString();
        } catch (Exception e) {
            return "Error fetching URL: " + e.getMessage();
        }
    }

    /**
     * Extract text from HTML
     */
    private String extractText(String html) {
        // Remove script and style tags
        String result = SCRIPT_PATTERN.matcher(html).replaceAll("");
        result = STYLE_PATTERN.matcher(result).replaceAll("");

        // Remove all HTML tags
        result = TAG_PATTERN.matcher(result).replaceAll(" ");

        // Normalize whitespace
        result = WHITESPACE_PATTERN.matcher(result.trim()).replaceAll(" ");

        return result;
    }
}
