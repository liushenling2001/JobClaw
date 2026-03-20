package io.jobclaw.mcp;

import java.util.ArrayList;
import java.util.List;

public class MCPServerInfo {

    private String name;
    private String description;
    private String version;
    private List<String> capabilities;

    public MCPServerInfo() {
        this.capabilities = new ArrayList<>();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public List<String> getCapabilities() { return capabilities; }
    public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }
}
