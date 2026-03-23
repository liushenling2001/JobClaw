package io.jobclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 工具配置
 */
@Component
@ConfigurationProperties(prefix = "tools")
public class ToolsConfig {

    private WebToolsConfig web;
    private SkillsToolConfig skills;

    public ToolsConfig() {
        this.web = new WebToolsConfig();
        this.skills = new SkillsToolConfig();
    }

    public WebToolsConfig getWeb() { return web; }
    public void setWeb(WebToolsConfig web) { this.web = web; }
    public SkillsToolConfig getSkills() { return skills; }
    public void setSkills(SkillsToolConfig skills) { this.skills = skills; }

    public static class WebToolsConfig {
        private WebSearchConfig search;

        public WebToolsConfig() {
            this.search = new WebSearchConfig();
        }

        public WebSearchConfig getSearch() { return search; }
        public void setSearch(WebSearchConfig search) { this.search = search; }
    }

    public static class WebSearchConfig {
        private String apiKey;
        private String secretKey;
        private int maxResults;

        public WebSearchConfig() {
            this.apiKey = "";
            this.secretKey = "";
            this.maxResults = 5;
        }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
    }

    public static class SkillsToolConfig {
        private List<RegistryConfig> registries;
        private boolean allowGlobalSearch;
        private String githubToken;

        public SkillsToolConfig() {
            this.registries = new ArrayList<>();
            this.allowGlobalSearch = false;
            this.githubToken = "";
        }

        public List<RegistryConfig> getRegistries() { return registries; }
        public void setRegistries(List<RegistryConfig> registries) { this.registries = registries; }
        public boolean isAllowGlobalSearch() { return allowGlobalSearch; }
        public void setAllowGlobalSearch(boolean allowGlobalSearch) { this.allowGlobalSearch = allowGlobalSearch; }
        public String getGithubToken() { return githubToken; }
        public void setGithubToken(String githubToken) { this.githubToken = githubToken; }
    }

    public static class RegistryConfig {
        private String name;
        private String repo;
        private String description;
        private boolean enabled;

        public RegistryConfig() {
            this.enabled = true;
        }

        public RegistryConfig(String name, String repo, String description) {
            this.name = name;
            this.repo = repo;
            this.description = description;
            this.enabled = true;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getRepo() { return repo; }
        public void setRepo(String repo) { this.repo = repo; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
