package io.jobclaw.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Tool> tools;
    private final Map<String, Long> callCounts;
    private final Map<String, Long> lastCallTimes;

    public ToolRegistry() {
        this.tools = new ConcurrentHashMap<>();
        this.callCounts = new ConcurrentHashMap<>();
        this.lastCallTimes = new ConcurrentHashMap<>();
    }

    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
        callCounts.put(tool.getName(), 0L);
        lastCallTimes.put(tool.getName(), 0L);
        logger.info("Registered tool: {}", tool.getName());
    }

    public void unregister(String name) {
        tools.remove(name);
        logger.info("Unregistered tool: {}", name);
    }

    public String execute(String name, Map<String, Object> args) throws Exception {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }

        long startTime = System.currentTimeMillis();
        String result = tool.execute(args);
        long duration = System.currentTimeMillis() - startTime;

        callCounts.put(name, callCounts.getOrDefault(name, 0L) + 1);
        lastCallTimes.put(name, System.currentTimeMillis());

        logger.debug("Executed tool {} in {}ms", name, duration);
        return result;
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }

    public Set<String> getToolNames() {
        return tools.keySet();
    }

    public List<Map<String, Object>> getDefinitions() {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (Tool tool : tools.values()) {
            definitions.add(tool.toDefinition());
        }
        return definitions;
    }

    public String getSummaries() {
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools.values()) {
            sb.append("- **").append(tool.getName()).append("**: ");
            sb.append(tool.getDescription()).append("\n");
        }
        return sb.toString();
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_tools", tools.size());
        stats.put("call_counts", callCounts);
        stats.put("last_call_times", lastCallTimes);
        return stats;
    }
}
