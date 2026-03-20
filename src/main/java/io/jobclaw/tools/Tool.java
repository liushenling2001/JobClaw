package io.jobclaw.tools;

import java.util.Map;

public interface Tool {

    String getName();

    String getDescription();

    Map<String, Object> getParameters();

    String execute(Map<String, Object> args) throws Exception;

    default Map<String, Object> toDefinition() {
        Map<String, Object> definition = new java.util.HashMap<>();
        definition.put("type", "function");

        Map<String, Object> function = new java.util.HashMap<>();
        function.put("name", getName());
        function.put("description", getDescription());
        function.put("parameters", getParameters());

        definition.put("function", function);
        return definition;
    }
}
