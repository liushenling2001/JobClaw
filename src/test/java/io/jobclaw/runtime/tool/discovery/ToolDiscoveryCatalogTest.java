package io.jobclaw.runtime.tool.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolDiscoveryCatalogTest {

    @Test
    void shouldFindOptionalToolsWithEnglishAndChineseCapabilityQueries() {
        ToolDiscoveryCatalog catalog = new ToolDiscoveryCatalog(List.of(
                callback("web_search", "Search the web for current information"),
                callback("cron", "Schedule reminders and recurring tasks"),
                callback("memory", "Store and recall durable memory")
        ));

        assertEquals("web_search", catalog.search("latest web information", 3).getFirst().definition().name());
        assertEquals("cron", catalog.search("明天提醒我", 3).getFirst().definition().name());
        assertEquals("memory", catalog.search("记住这个偏好", 3).getFirst().definition().name());
    }

    @Test
    void shouldRequireSearchBeforeOptionalToolUse() {
        ToolDiscoveryCatalog catalog = new ToolDiscoveryCatalog(List.of(
                callback("web_search", "Search the web for current information")
        ));
        ToolDiscoveryCallbacks.CallbackPair callbacks = ToolDiscoveryCallbacks.create(
                catalog,
                (callback, arguments) -> "invoked:" + callback.getToolDefinition().name() + ":" + arguments
        );

        String rejected = callbacks.use().call("""
                {"name":"web_search","arguments":{"query":"Spring AI"}}
                """);
        assertTrue(rejected.contains("was not returned by tool_search"));

        String search = callbacks.search().call("""
                {"query":"web search","limit":3}
                """);
        assertTrue(search.contains("\"name\":\"web_search\""));

        String result = callbacks.use().call("""
                {"name":"web_search","arguments":{"query":"Spring AI"}}
                """);
        assertTrue(result.startsWith("invoked:web_search:"));
        assertTrue(result.contains("\"query\":\"Spring AI\""));
    }

    private ToolCallback callback(String name, String description) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String request) {
                return "ok";
            }
        };
    }
}
