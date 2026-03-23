package io.jobclaw.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;

/**
 * LLM 提供商配置
 */
public class ProvidersConfig {

    private ProviderConfig openrouter;
    private ProviderConfig anthropic;
    private ProviderConfig openai;
    private ProviderConfig zhipu;
    private ProviderConfig gemini;
    private ProviderConfig dashscope;
    private ProviderConfig ollama;

    public ProvidersConfig() {
        this.openrouter = new ProviderConfig(getDefaultApiBase("openrouter"));
        this.anthropic = new ProviderConfig(getDefaultApiBase("anthropic"));
        this.openai = new ProviderConfig(getDefaultApiBase("openai"));
        this.zhipu = new ProviderConfig(getDefaultApiBase("zhipu"));
        this.gemini = new ProviderConfig(getDefaultApiBase("gemini"));
        this.dashscope = new ProviderConfig(getDefaultApiBase("dashscope"));
        this.ollama = new ProviderConfig(getDefaultApiBase("ollama"));
    }

    public ProviderConfig getOpenrouter() { return openrouter; }
    public void setOpenrouter(ProviderConfig openrouter) { this.openrouter = openrouter; }
    public ProviderConfig getAnthropic() { return anthropic; }
    public void setAnthropic(ProviderConfig anthropic) { this.anthropic = anthropic; }
    public ProviderConfig getOpenai() { return openai; }
    public void setOpenai(ProviderConfig openai) { this.openai = openai; }
    public ProviderConfig getZhipu() { return zhipu; }
    public void setZhipu(ProviderConfig zhipu) { this.zhipu = zhipu; }
    public ProviderConfig getGemini() { return gemini; }
    public void setGemini(ProviderConfig gemini) { this.gemini = gemini; }
    public ProviderConfig getDashscope() { return dashscope; }
    public void setDashscope(ProviderConfig dashscope) { this.dashscope = dashscope; }
    public ProviderConfig getOllama() { return ollama; }
    public void setOllama(ProviderConfig ollama) { this.ollama = ollama; }

    @JsonIgnore
    public List<ProviderConfig> getAllProviders() {
        return Arrays.asList(openrouter, anthropic, openai, gemini, zhipu, dashscope, ollama);
    }

    @JsonIgnore
    public Optional<ProviderConfig> getFirstValidProvider() {
        return getAllProviders().stream()
                .filter(p -> p != null && p.isValid())
                .findFirst();
    }

    @JsonIgnore
    public Optional<ProviderWithName> getFirstAvailableProvider() {
        if (ollama != null && ollama.isValidForLocal()) {
            return Optional.of(new ProviderWithName("ollama", ollama));
        }
        List<ProviderWithName> providers = Arrays.asList(
                new ProviderWithName("openrouter", openrouter),
                new ProviderWithName("openai", openai),
                new ProviderWithName("anthropic", anthropic),
                new ProviderWithName("zhipu", zhipu),
                new ProviderWithName("dashscope", dashscope),
                new ProviderWithName("gemini", gemini)
        );
        return providers.stream()
                .filter(p -> p.config != null && p.config.isValid())
                .findFirst();
    }

    public String getProviderName(ProviderConfig provider) {
        if (provider == openrouter) return "openrouter";
        if (provider == anthropic) return "anthropic";
        if (provider == openai) return "openai";
        if (provider == gemini) return "gemini";
        if (provider == zhipu) return "zhipu";
        if (provider == dashscope) return "dashscope";
        if (provider == ollama) return "ollama";
        return "unknown";
    }

    public static String getDefaultApiBase(String providerName) {
        return switch (providerName) {
            case "openrouter" -> "https://openrouter.ai/api/v1";
            case "anthropic" -> "https://api.anthropic.com/v1";
            case "openai" -> "https://api.openai.com/v1";
            case "gemini" -> "https://generativelanguage.googleapis.com/v1beta";
            case "zhipu" -> "https://open.bigmodel.cn/api/paas/v4";
            case "dashscope" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "ollama" -> "http://localhost:11434/v1";
            default -> "https://openrouter.ai/api/v1";
        };
    }

    public static class ProviderConfig {
        private String apiKey;
        
        @JsonProperty("baseUrl")
        private String apiBase;

        public ProviderConfig() {
            this.apiKey = "";
            this.apiBase = "";
        }

        public ProviderConfig(String defaultApiBase) {
            this.apiKey = "";
            this.apiBase = defaultApiBase;
        }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        
        @JsonProperty("baseUrl")
        public String getApiBase() { return apiBase; }
        public void setApiBase(String apiBase) { this.apiBase = apiBase; }

        @JsonIgnore
        public boolean isValid() {
            return apiKey != null && !apiKey.isEmpty();
        }

        @JsonIgnore
        public boolean isValidForLocal() {
            return hasApiBase();
        }

        @JsonIgnore
        public boolean hasApiBase() {
            return apiBase != null && !apiBase.isEmpty();
        }

        public String getApiBaseOrDefault(String defaultBase) {
            return (apiBase != null && !apiBase.isEmpty()) ? apiBase : defaultBase;
        }
    }

    public static class ProviderWithName {
        public final String name;
        public final ProviderConfig config;

        public ProviderWithName(String name, ProviderConfig config) {
            this.name = name;
            this.config = config;
        }
    }
}
