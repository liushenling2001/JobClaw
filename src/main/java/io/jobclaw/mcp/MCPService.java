package io.jobclaw.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP (Model Context Protocol) 服务
 * 
 * 提供与外部 MCP 服务器的连接和资源访问能力
 * 
 * 功能：
 * - 连接 MCP 服务器
 * - 列出可用资源
 * - 读取资源内容
 * - 调用 MCP 工具
 * - 管理提示词模板
 */
@Component
public class MCPService {
    
    private static final Logger logger = LoggerFactory.getLogger(MCPService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final HttpClient httpClient;
    private final Map<String, MCPServer> connectedServers;
    
    public MCPService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.connectedServers = new ConcurrentHashMap<>();
    }
    
    /**
     * 连接到 MCP 服务器
     */
    public MCPServer connect(String serverId, String url) {
        try {
            // 获取服务器信息
            Map<String, Object> serverInfo = getServerInfo(url);
            
            MCPServer server = new MCPServer();
            server.setId(serverId);
            server.setUrl(url);
            server.setName((String) serverInfo.getOrDefault("name", serverId));
            server.setVersion((String) serverInfo.getOrDefault("version", "1.0.0"));
            server.setConnected(true);
            
            // 获取服务器能力
            server.setCapabilities(getServerCapabilities(url));
            
            // 获取资源列表
            server.setResources(listResources(url));
            
            // 获取工具列表
            server.setTools(listTools(url));
            
            // 获取提示词列表
            server.setPrompts(listPrompts(url));
            
            connectedServers.put(serverId, server);
            logger.info("Connected to MCP server: {} ({})", serverId, url);
            
            return server;
            
        } catch (Exception e) {
            logger.error("Failed to connect to MCP server: {}", serverId, e);
            MCPServer server = new MCPServer();
            server.setId(serverId);
            server.setUrl(url);
            server.setConnected(false);
            server.setError(e.getMessage());
            return server;
        }
    }
    
    /**
     * 断开 MCP 服务器连接
     */
    public void disconnect(String serverId) {
        connectedServers.remove(serverId);
        logger.info("Disconnected from MCP server: {}", serverId);
    }
    
    /**
     * 获取已连接的服务器
     */
    public MCPServer getServer(String serverId) {
        return connectedServers.get(serverId);
    }
    
    /**
     * 获取所有已连接的服务器
     */
    public List<MCPServer> getAllServers() {
        return new ArrayList<>(connectedServers.values());
    }
    
    /**
     * 获取服务器信息
     */
    private Map<String, Object> getServerInfo(String url) throws IOException, InterruptedException {
        String response = sendRequest(url, "info", null);
        return objectMapper.readValue(response, Map.class);
    }
    
    /**
     * 获取服务器能力
     */
    private Map<String, Object> getServerCapabilities(String url) throws IOException, InterruptedException {
        String response = sendRequest(url, "capabilities", null);
        return objectMapper.readValue(response, Map.class);
    }
    
    /**
     * 列出资源
     */
    private List<Map<String, Object>> listResources(String url) throws IOException, InterruptedException {
        String response = sendRequest(url, "resources/list", null);
        JsonNode root = objectMapper.readTree(response);
        return objectMapper.convertValue(root.get("resources"), List.class);
    }
    
    /**
     * 读取资源
     */
    @SuppressWarnings("unchecked")
    public String readResource(String url, String resourceUri) throws IOException, InterruptedException {
        Map<String, Object> params = new HashMap<>();
        params.put("uri", resourceUri);
        String response = sendRequest(url, "resources/read", params);
        JsonNode root = objectMapper.readTree(response);
        return root.get("content").asText();
    }
    
    /**
     * 列出工具
     */
    private List<Map<String, Object>> listTools(String url) throws IOException, InterruptedException {
        String response = sendRequest(url, "tools/list", null);
        JsonNode root = objectMapper.readTree(response);
        return objectMapper.convertValue(root.get("tools"), List.class);
    }
    
    /**
     * 调用工具
     */
    public String callTool(String url, String toolName, Map<String, Object> arguments) throws IOException, InterruptedException {
        Map<String, Object> params = new HashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments);
        String response = sendRequest(url, "tools/call", params);
        JsonNode root = objectMapper.readTree(response);
        return root.get("result").toString();
    }
    
    /**
     * 列出提示词
     */
    private List<Map<String, Object>> listPrompts(String url) throws IOException, InterruptedException {
        String response = sendRequest(url, "prompts/list", null);
        JsonNode root = objectMapper.readTree(response);
        return objectMapper.convertValue(root.get("prompts"), List.class);
    }
    
    /**
     * 获取提示词
     */
    @SuppressWarnings("unchecked")
    public String getPrompt(String url, String promptName, Map<String, Object> arguments) throws IOException, InterruptedException {
        Map<String, Object> params = new HashMap<>();
        params.put("name", promptName);
        params.put("arguments", arguments);
        String response = sendRequest(url, "prompts/get", params);
        JsonNode root = objectMapper.readTree(response);
        return root.get("prompt").asText();
    }
    
    /**
     * 发送 HTTP 请求
     */
    private String sendRequest(String baseUrl, String method, Map<String, Object> params) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + (baseUrl.endsWith("/") ? "" : "/") + method);
        
        String requestBody = params != null ? objectMapper.writeValueAsString(params) : "{}";
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("MCP request failed: " + response.statusCode() + " - " + response.body());
        }
        
        return response.body();
    }
    
    /**
     * MCP 服务器信息
     */
    public static class MCPServer {
        private String id;
        private String url;
        private String name;
        private String version;
        private boolean connected;
        private String error;
        private Map<String, Object> capabilities;
        private List<Map<String, Object>> resources;
        private List<Map<String, Object>> tools;
        private List<Map<String, Object>> prompts;
        
        // Getters and Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        
        public boolean isConnected() { return connected; }
        public void setConnected(boolean connected) { this.connected = connected; }
        
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        
        public Map<String, Object> getCapabilities() { return capabilities; }
        public void setCapabilities(Map<String, Object> capabilities) { this.capabilities = capabilities; }
        
        public List<Map<String, Object>> getResources() { return resources; }
        public void setResources(List<Map<String, Object>> resources) { this.resources = resources; }
        
        public List<Map<String, Object>> getTools() { return tools; }
        public void setTools(List<Map<String, Object>> tools) { this.tools = tools; }
        
        public List<Map<String, Object>> getPrompts() { return prompts; }
        public void setPrompts(List<Map<String, Object>> prompts) { this.prompts = prompts; }
    }
}
