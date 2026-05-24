package io.jobclaw.providers;

import java.util.Map;

/**
 * 工具调用表示
 */
public class ToolCall {

    private String id;
    private String type;
    private Function function;

    public ToolCall() {
        this.type = "function";
    }

    public ToolCall(String id, String name, String arguments) {
        this.id = id;
        this.type = "function";
        this.function = new Function(name, arguments);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Function getFunction() { return function; }
    public void setFunction(Function function) { this.function = function; }

    public static class Function {
        private String name;
        private String arguments;

        public Function() {}
        public Function(String name, String arguments) {
            this.name = name;
            this.arguments = arguments;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getArguments() { return arguments; }
        public void setArguments(String arguments) { this.arguments = arguments; }
    }
}
