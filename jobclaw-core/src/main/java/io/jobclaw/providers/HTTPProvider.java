package io.jobclaw.providers;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * HTTP-based LLM Provider implementation
 * Note: This bean is manually configured in AgentBeansConfig, not auto-registered by Spring
 */
public class HTTPProvider implements LLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(HTTPProvider.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final OkHttpClient httpClient;
    private String apiKey;
    private String apiBase;
    private String model;

    public HTTPProvider() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    public HTTPProvider(String apiKey, String apiBase, String model) {
        this();
        this.apiKey = apiKey;
        this.apiBase = apiBase;
        this.model = model;
    }

    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, String model, LLMOptions options) {
        String targetModel = model != null ? model : this.model;

        try {
            Map<String, Object> requestBody = buildRequestBody(messages, tools, targetModel, options);
            Request request = createRequest(requestBody);

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP error: " + response.code());
                }

                String responseBody = response.body().string();
                return parseResponse(responseBody);
            }
        } catch (Exception e) {
            logger.error("Chat request failed", e);
            LLMResponse errorResponse = new LLMResponse("Error: " + e.getMessage());
            return errorResponse;
        }
    }

    @Override
    public LLMResponse chatStream(List<Message> messages, List<ToolDefinition> tools, String model, LLMOptions options, StreamCallback callback) {
        // Simplified streaming implementation
        return chat(messages, tools, model, options);
    }

    private Map<String, Object> buildRequestBody(List<Message> messages, List<ToolDefinition> tools, String model, LLMOptions options) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);

        List<Map<String, Object>> formattedMessages = new ArrayList<>();
        for (Message msg : messages) {
            Map<String, Object> formatted = new LinkedHashMap<>();
            formatted.put("role", msg.getRole());
            formatted.put("content", msg.getContent());
            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                List<Map<String, Object>> toolCalls = new ArrayList<>();
                for (ToolCall tc : msg.getToolCalls()) {
                    Map<String, Object> tcMap = new LinkedHashMap<>();
                    tcMap.put("id", tc.getId());
                    tcMap.put("type", "function");
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", tc.getFunction().getName());
                    fn.put("arguments", tc.getFunction().getArguments());
                    tcMap.put("function", fn);
                    toolCalls.add(tcMap);
                }
                formatted.put("tool_calls", toolCalls);
            }
            if (msg.getToolCallId() != null) {
                formatted.put("tool_call_id", msg.getToolCallId());
            }
            formattedMessages.add(formatted);
        }
        body.put("messages", formattedMessages);

        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> formattedTools = new ArrayList<>();
            for (ToolDefinition tool : tools) {
                Map<String, Object> toolMap = new LinkedHashMap<>();
                toolMap.put("type", "function");
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", tool.getFunction().getName());
                fn.put("description", tool.getFunction().getDescription());
                fn.put("parameters", tool.getFunction().getParameters());
                toolMap.put("function", fn);
                formattedTools.add(toolMap);
            }
            body.put("tools", formattedTools);
        }

        if (options != null) {
            if (options.getTemperature() != null) {
                body.put("temperature", options.getTemperature());
            }
            if (options.getMaxTokens() != null) {
                body.put("max_tokens", options.getMaxTokens());
            }
            if (options.getStream() != null) {
                body.put("stream", options.getStream());
            }
        }

        return body;
    }

    private Request createRequest(Map<String, Object> body) throws IOException {
        RequestBody requestBody = RequestBody.create(
                objectMapper.writeValueAsString(body),
                MediaType.parse("application/json")
        );

        return new Request.Builder()
                .url(apiBase + "/chat/completions")
                .post(requestBody)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .build();
    }

    private LLMResponse parseResponse(String responseBody) throws IOException {
        Map<String, Object> json = objectMapper.readValue(responseBody, Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) json.get("choices");

        if (choices == null || choices.isEmpty()) {
            return new LLMResponse("");
        }

        Map<String, Object> choice = choices.get(0);
        Map<String, Object> assistantMessage = (Map<String, Object>) choice.get("message");

        String content = (String) assistantMessage.get("content");
        List<ToolCall> toolCalls = null;

        Object toolCallsObj = assistantMessage.get("tool_calls");
        if (toolCallsObj instanceof List) {
            toolCalls = parseToolCalls((List<Map<String, Object>>) toolCallsObj);
        }

        LLMResponse response = new LLMResponse(content, toolCalls);

        Map<String, Object> usageMap = (Map<String, Object>) json.get("usage");
        if (usageMap != null) {
            LLMResponse.Usage usage = new LLMResponse.Usage();
            usage.setPromptTokens(getInt(usageMap, "prompt_tokens"));
            usage.setCompletionTokens(getInt(usageMap, "completion_tokens"));
            usage.setTotalTokens(getInt(usageMap, "total_tokens"));
            response.setUsage(usage);
        }

        return response;
    }

    private List<ToolCall> parseToolCalls(List<Map<String, Object>> toolCallsObj) {
        List<ToolCall> toolCalls = new ArrayList<>();
        for (Map<String, Object> tcObj : toolCallsObj) {
            String id = (String) tcObj.get("id");
            String type = (String) tcObj.get("type");
            Map<String, Object> fnObj = (Map<String, Object>) tcObj.get("function");

            if (fnObj != null) {
                String name = (String) fnObj.get("name");
                String arguments = (String) fnObj.get("arguments");

                ToolCall tc = new ToolCall(id, name, arguments);
                toolCalls.add(tc);
            }
        }
        return toolCalls;
    }

    private int getInt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getApiBase() {
        return apiBase;
    }

    public String getModel() {
        return model;
    }
}
