package io.jobclaw.providers;

import java.util.*;

/**
 * 工具定义，用于向 LLM 描述可调用工具
 */
public class ToolDefinition {

    private String type;
    private Function function;

    public ToolDefinition() {
        this.type = "function";
    }

    public ToolDefinition(String name, String description, Map<String, Object> parameters) {
        this.type = "function";
        this.function = new Function(name, description, parameters);
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Function getFunction() { return function; }
    public void setFunction(Function function) { this.function = function; }

    public static class Function {
        private String name;
        private String description;
        private Map<String, Object> parameters;

        public Function() {}
        public Function(String name, String description, Map<String, Object> parameters) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Map<String, Object> getParameters() { return parameters; }
        public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }
    }

    public static ToolDefinition of(String name, String description, Map<String, Object> parameters) {
        return new ToolDefinition(name, description, parameters);
    }
}
