package io.jobclaw.config;

import java.util.*;

/**
 * 模型配置
 */
public class ModelsConfig {

    private Map<String, ModelDefinition> definitions = new HashMap<>();

    public ModelsConfig() {
        // 通义千问系列
        definitions.put("qwen3-max", new ModelDefinition("dashscope", "qwen3-max", 200000));
        definitions.put("qwen3.5-plus", new ModelDefinition("dashscope", "qwen3.5-plus", 128000));

        // GPT 系列
        definitions.put("gpt-4o", new ModelDefinition("openai", "gpt-4o", 128000));
        definitions.put("gpt-4o-mini", new ModelDefinition("openai", "gpt-4o-mini", 128000));

        // Claude 系列
        definitions.put("claude-3-5-sonnet-20241022", new ModelDefinition("anthropic", "claude-3-5-sonnet-20241022", 200000));
        definitions.put("claude-3-5-haiku-20241022", new ModelDefinition("anthropic", "claude-3-5-haiku-20241022", 200000));

        // 智谱系列
        definitions.put("glm-4-plus", new ModelDefinition("zhipu", "glm-4-plus", 128000));
        definitions.put("glm-4-flash", new ModelDefinition("zhipu", "glm-4-flash", 128000));

        // Gemini 系列
        definitions.put("gemini-2.0-flash-exp", new ModelDefinition("gemini", "gemini-2.0-flash-exp", 1000000));

        // 本地模型
        definitions.put("llama3.1", new ModelDefinition("ollama", "llama3.1", 128000));
    }

    public Map<String, ModelDefinition> getDefinitions() { return definitions; }
    public void setDefinitions(Map<String, ModelDefinition> definitions) { this.definitions = definitions; }

    public static class ModelDefinition {
        private String provider;
        private String model;
        private Integer maxContextSize;
        private String description;

        public ModelDefinition() {}
        public ModelDefinition(String provider, String model, Integer maxContextSize) {
            this.provider = provider;
            this.model = model;
            this.maxContextSize = maxContextSize;
        }

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public Integer getMaxContextSize() { return maxContextSize; }
        public void setMaxContextSize(Integer maxContextSize) { this.maxContextSize = maxContextSize; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}
