package io.jobclaw.runtime.tool.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ToolDiscoveryCallbacks {

    public static final String SEARCH_TOOL_NAME = "tool_search";
    public static final String USE_TOOL_NAME = "tool_use";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_SEARCH_LIMIT = 5;
    private static final int MAX_DESCRIPTION_CHARS = 800;
    private static final int MAX_SCHEMA_CHARS = 2_000;

    private ToolDiscoveryCallbacks() {
    }

    public static CallbackPair create(ToolDiscoveryCatalog catalog, ToolInvoker invoker) {
        Set<String> discoveredNames = ConcurrentHashMap.newKeySet();
        return new CallbackPair(
                searchCallback(catalog, discoveredNames),
                useCallback(catalog, discoveredNames, invoker)
        );
    }

    private static ToolCallback searchCallback(ToolDiscoveryCatalog catalog, Set<String> discoveredNames) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(SEARCH_TOOL_NAME)
                .description("Search optional JobClaw tools by capability. Use this when the directly available tools do not cover the task. Search with concise capability terms; then invoke a returned tool through tool_use.")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "query": {
                              "type": "string",
                              "description": "Capability to search for, for example web search, reminder, PDF, memory, or multi-agent collaboration"
                            },
                            "limit": {
                              "type": "integer",
                              "minimum": 1,
                              "maximum": 8,
                              "default": 5
                            }
                          },
                          "required": ["query"],
                          "additionalProperties": false
                        }
                        """)
                .build();
        return new SimpleToolCallback(definition, request -> {
            try {
                JsonNode input = MAPPER.readTree(request);
                String query = input.path("query").asText("");
                int limit = input.path("limit").asInt(DEFAULT_SEARCH_LIMIT);
                if (query.isBlank()) {
                    return "Error: query is required";
                }
                ArrayNode tools = MAPPER.createArrayNode();
                for (ToolDiscoveryCatalog.Match match : catalog.search(query, limit)) {
                    String name = match.definition().name();
                    discoveredNames.add(name);
                    ObjectNode tool = tools.addObject();
                    tool.put("name", name);
                    tool.put("description", truncate(match.definition().description(), MAX_DESCRIPTION_CHARS));
                    tool.put("inputSchema", truncate(match.definition().inputSchema(), MAX_SCHEMA_CHARS));
                    tool.put("score", Math.round(match.score() * 1_000.0d) / 1_000.0d);
                }
                ObjectNode output = MAPPER.createObjectNode();
                output.put("query", query);
                output.set("tools", tools);
                if (tools.isEmpty()) {
                    output.put("message", "No optional tool matched. Rephrase the capability or use the directly available tools.");
                } else {
                    output.put("message", "Invoke one returned tool with tool_use and arguments matching its inputSchema.");
                }
                return MAPPER.writeValueAsString(output);
            } catch (Exception e) {
                return "Error: invalid tool_search arguments: " + safeMessage(e);
            }
        });
    }

    private static ToolCallback useCallback(ToolDiscoveryCatalog catalog,
                                            Set<String> discoveredNames,
                                            ToolInvoker invoker) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(USE_TOOL_NAME)
                .description("Invoke an optional tool returned by tool_search. The target tool still runs through JobClaw's normal ToolRuntime, event tracking, timeout, context_ref, and completion evidence handling.")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "name": {
                              "type": "string",
                              "description": "Exact tool name returned by tool_search"
                            },
                            "arguments": {
                              "type": "object",
                              "description": "Arguments matching the selected tool inputSchema"
                            }
                          },
                          "required": ["name", "arguments"],
                          "additionalProperties": false
                        }
                        """)
                .build();
        return new SimpleToolCallback(definition, request -> {
            try {
                JsonNode input = MAPPER.readTree(request);
                String name = input.path("name").asText("").trim();
                if (name.isBlank()) {
                    return "Error: tool name is required";
                }
                if (!discoveredNames.contains(name)) {
                    return "Error: tool '" + name + "' was not returned by tool_search in this run. Search for it first.";
                }
                ToolCallback target = catalog.find(name).orElse(null);
                if (target == null) {
                    return "Error: optional tool '" + name + "' is unavailable";
                }
                JsonNode arguments = input.get("arguments");
                if (arguments == null || arguments.isNull()) {
                    return "Error: arguments are required";
                }
                if (!arguments.isObject()) {
                    return "Error: arguments must be a JSON object";
                }
                return invoker.invoke(target, MAPPER.writeValueAsString(arguments));
            } catch (Exception e) {
                return "Error: invalid tool_use arguments: " + safeMessage(e);
            }
        });
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...";
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message != null && !message.isBlank()
                ? message
                : exception.getClass().getSimpleName();
    }

    public record CallbackPair(ToolCallback search, ToolCallback use) {
    }

    @FunctionalInterface
    public interface ToolInvoker {
        String invoke(ToolCallback callback, String arguments);
    }

    @FunctionalInterface
    private interface ToolCall {
        String call(String request);
    }

    private record SimpleToolCallback(ToolDefinition toolDefinition, ToolCall toolCall) implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public String call(String request) {
            return toolCall.call(request);
        }
    }
}
