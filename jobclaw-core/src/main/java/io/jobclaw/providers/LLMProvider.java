package io.jobclaw.providers;

import java.util.List;
import java.util.Map;

/**
 * LLM Provider 接口
 */
public interface LLMProvider {

    LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, String model, LLMOptions options);

    LLMResponse chatStream(List<Message> messages, List<ToolDefinition> tools, String model, LLMOptions options, StreamCallback callback);

    default LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        return chat(messages, tools, null, null);
    }

    default LLMResponse chat(List<Message> messages) {
        return chat(messages, null, null, null);
    }

    interface StreamCallback {
        void onToken(String token);
        void onComplete(LLMResponse response);
        void onError(Throwable error);
    }

    class LLMOptions {
        private Double temperature;
        private Integer maxTokens;
        private Boolean stream;
        private Map<String, Object> extra;

        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
        public Boolean getStream() { return stream; }
        public void setStream(Boolean stream) { this.stream = stream; }
        public Map<String, Object> getExtra() { return extra; }
        public void setExtra(Map<String, Object> extra) { this.extra = extra; }

        public static LLMOptions create() {
            return new LLMOptions();
        }

        public LLMOptions withTemperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public LLMOptions withMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }
    }
}
