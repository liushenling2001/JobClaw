package io.jobclaw.config;

import java.util.*;

/**
 * MCP 服务器配置
 */
public class MCPServersConfig {

    private boolean enabled;
    private List<MCPServerConfig> servers;

    public MCPServersConfig() {
        this.enabled = false;
        this.servers = new ArrayList<>();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<MCPServerConfig> getServers() { return servers; }
    public void setServers(List<MCPServerConfig> servers) { this.servers = servers; }

    public static class MCPServerConfig {
        private String name;
        private String description;
        private String type;
        private String endpoint;
        private String apiKey;
        private String command;
        private List<String> args;
        private Map<String, String> env;
        private boolean enabled;
        private int timeout;

        public MCPServerConfig() {
            this.type = "sse";
            this.enabled = true;
            this.timeout = 30000;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> args) { this.args = args; }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = env; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }

        public boolean isStdio() {
            return "stdio".equalsIgnoreCase(type);
        }

        public boolean isStreamableHttp() {
            return "streamable-http".equalsIgnoreCase(type);
        }
    }
}
