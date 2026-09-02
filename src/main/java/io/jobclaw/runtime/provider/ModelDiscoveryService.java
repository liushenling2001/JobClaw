package io.jobclaw.runtime.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jobclaw.config.Config;
import io.jobclaw.config.ProvidersConfig;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class ModelDiscoveryService {

    private static final int ERROR_BODY_LIMIT = 500;

    private final Config config;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    @Autowired
    public ModelDiscoveryService(Config config, ObjectMapper objectMapper) {
        this(config, objectMapper, new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build());
    }

    ModelDiscoveryService(Config config, ObjectMapper objectMapper, OkHttpClient httpClient) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public DiscoveryResult discover(String providerName) throws IOException {
        String provider = normalizeProvider(providerName);
        ProvidersConfig.ProviderConfig providerConfig = config.getProviderConfigByName(provider);
        if (providerConfig == null) {
            throw new IllegalArgumentException("Unknown provider: " + providerName);
        }
        if (providerConfig.getApiBase() == null || providerConfig.getApiBase().isBlank()) {
            throw new IllegalArgumentException("Provider '" + provider + "' has no API Base URL");
        }

        try {
            return requestModels(provider, providerConfig, false);
        } catch (ModelDiscoveryException firstFailure) {
            if (!"ollama".equals(provider)) {
                throw firstFailure;
            }
            return requestModels(provider, providerConfig, true);
        }
    }

    private DiscoveryResult requestModels(String provider,
                                          ProvidersConfig.ProviderConfig providerConfig,
                                          boolean ollamaNative) throws IOException {
        String endpoint = buildEndpoint(provider, providerConfig.getApiBase(), ollamaNative);
        Request.Builder request = new Request.Builder()
                .url(endpoint)
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", "JobClaw/1.0");

        String apiKey = providerConfig.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            if ("anthropic".equals(provider)) {
                request.header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01");
            } else if (!"gemini".equals(provider)) {
                request.header("Authorization", "Bearer " + apiKey);
            }
        }

        try (Response response = httpClient.newCall(request.build()).execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody != null ? responseBody.string() : "";
            if (!response.isSuccessful()) {
                throw new ModelDiscoveryException(response.code(), response.message(), abbreviate(body));
            }
            List<DiscoveredModel> models = parseModels(body);
            if (models.isEmpty()) {
                throw new ModelDiscoveryException(response.code(), "No models found in provider response", abbreviate(body));
            }
            return new DiscoveryResult(provider, List.copyOf(models));
        }
    }

    String buildEndpoint(String provider, String apiBase, boolean ollamaNative) {
        String normalizedBase = apiBase.trim().replaceAll("/+$", "");
        if (ollamaNative) {
            normalizedBase = normalizedBase.replaceFirst("/v1$", "");
            return normalizedBase + "/api/tags";
        }

        String endpoint = normalizedBase.endsWith("/models") ? normalizedBase : normalizedBase + "/models";
        if ("gemini".equals(provider)) {
            String apiKey = config.getProviderConfigByName(provider).getApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                HttpUrl url = HttpUrl.parse(endpoint);
                if (url != null) {
                    return url.newBuilder().addQueryParameter("key", apiKey).build().toString();
                }
            }
        }
        return endpoint;
    }

    List<DiscoveredModel> parseModels(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode candidates = root;
        if (root != null && root.isObject()) {
            if (root.path("data").isArray()) {
                candidates = root.path("data");
            } else if (root.path("models").isArray()) {
                candidates = root.path("models");
            }
        }
        if (candidates == null || !candidates.isArray()) {
            return List.of();
        }

        Map<String, DiscoveredModel> unique = new LinkedHashMap<>();
        for (JsonNode candidate : candidates) {
            String id;
            String displayName;
            String ownedBy;
            if (candidate.isTextual()) {
                id = candidate.asText();
                displayName = id;
                ownedBy = "";
            } else {
                id = firstText(candidate, "id", "name", "model");
                displayName = firstText(candidate, "display_name", "displayName", "name", "id", "model");
                ownedBy = firstText(candidate, "owned_by", "ownedBy", "publisher");
            }
            if (id == null || id.isBlank()) {
                continue;
            }
            id = stripGeminiPrefix(id.trim());
            displayName = displayName == null || displayName.isBlank() ? id : stripGeminiPrefix(displayName.trim());
            unique.putIfAbsent(id, new DiscoveredModel(id, displayName, ownedBy == null ? "" : ownedBy));
        }

        List<DiscoveredModel> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparing(DiscoveredModel::id, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private String stripGeminiPrefix(String value) {
        return value.startsWith("models/") ? value.substring("models/".length()) : value;
    }

    private String normalizeProvider(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            throw new IllegalArgumentException("Provider is required");
        }
        return providerName.trim().toLowerCase(Locale.ROOT);
    }

    private String abbreviate(String body) {
        String normalized = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return normalized.length() <= ERROR_BODY_LIMIT ? normalized : normalized.substring(0, ERROR_BODY_LIMIT) + "...";
    }

    public record DiscoveredModel(String id, String name, String ownedBy) {
    }

    public record DiscoveryResult(String provider, List<DiscoveredModel> models) {
    }

    public static class ModelDiscoveryException extends IOException {
        private final int statusCode;
        private final String responseBody;

        ModelDiscoveryException(int statusCode, String message, String responseBody) {
            super("Model discovery failed (HTTP " + statusCode + "): " + message
                    + (responseBody == null || responseBody.isBlank() ? "" : " - " + responseBody));
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getResponseBody() {
            return responseBody;
        }
    }
}
