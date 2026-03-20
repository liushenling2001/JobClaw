package io.jobclaw.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jobclaw.config.ToolsConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Skill Searcher - Search for installable skills from trusted markets and GitHub
 *
 * Uses a layered search strategy, prioritizing trusted skill markets (Skill Registry),
 * and only falling back to GitHub global search when explicitly enabled by user.
 */
public class SkillsSearcher {

    private static final Logger logger = LoggerFactory.getLogger(SkillsSearcher.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final String GITHUB_SEARCH_REPOS = GITHUB_API_BASE + "/search/repositories";
    private static final String GITHUB_SEARCH_CODE = GITHUB_API_BASE + "/search/code";
    private static final String GITHUB_CONTENTS_API = GITHUB_API_BASE + "/repos/%s/contents/%s";
    private static final String GITHUB_REPO_CONTENTS_API = GITHUB_API_BASE + "/repos/%s/contents";

    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final String USER_AGENT = "jobclaw/1.0";

    private final OkHttpClient httpClient;
    private final String githubToken;
    private final boolean allowGlobalSearch;
    private final List<SkillRegistry> registries;
    private volatile boolean lastSearchRateLimited = false;

    /**
     * Create skill searcher (using default configuration)
     *
     * By default, only searches from built-in trusted market sources, without enabling GitHub global search.
     */
    public SkillsSearcher() {
        this(null, false, null);
    }

    /**
     * Create skill searcher
     *
     * @param githubToken     GitHub Personal Access Token (optional, improves rate limits)
     * @param allowGlobalSearch Whether to allow GitHub global search
     * @param customRegistries  Custom skill market sources (null uses defaults)
     */
    public SkillsSearcher(String githubToken, boolean allowGlobalSearch,
                          List<SkillRegistry> customRegistries) {
        this.githubToken = githubToken;
        this.allowGlobalSearch = allowGlobalSearch;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        // Merge default and custom market sources
        this.registries = new ArrayList<>(SkillRegistry.getDefaultRegistries());
        if (customRegistries != null) {
            for (SkillRegistry custom : customRegistries) {
                if (custom.isEnabled() && registries.stream()
                        .noneMatch(r -> r.getRepo().equals(custom.getRepo()))) {
                    registries.add(custom);
                }
            }
        }
    }

    /**
     * Create skill searcher from ToolsConfig
     *
     * @param skillsConfig Skills tool configuration
     * @return Configured skill searcher
     */
    public static SkillsSearcher fromConfig(ToolsConfig.SkillsToolConfig skillsConfig) {
        if (skillsConfig == null) {
            return new SkillsSearcher();
        }

        List<SkillRegistry> customRegistries = new ArrayList<>();
        if (skillsConfig.getRegistries() != null) {
            for (ToolsConfig.RegistryConfig registryConfig : skillsConfig.getRegistries()) {
                if (registryConfig.isEnabled()) {
                    customRegistries.add(new SkillRegistry(
                            registryConfig.getName(),
                            registryConfig.getRepo(),
                            registryConfig.getDescription()
                    ));
                }
            }
        }

        String token = skillsConfig.getGithubToken();
        if (token != null && token.isEmpty()) {
            token = null;
        }

        return new SkillsSearcher(token, skillsConfig.isAllowGlobalSearch(), customRegistries);
    }

    /**
     * Search for skills
     *
     * Executes layered search by priority:
     * 1. Search from trusted skill market registry.json indexes
     * 2. Scan trusted repository directory structures for SKILL.md
     * 3. If global search enabled and results insufficient, fall back to GitHub global search
     *
     * @param query      Search keyword (describe needed skill functionality)
     * @param maxResults Maximum number of results to return
     * @return Search result list
     */
    public List<SkillSearchResult> search(String query, int maxResults) {
        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }

        int limit = maxResults > 0 && maxResults <= 10 ? maxResults : DEFAULT_MAX_RESULTS;
        List<SkillSearchResult> results = new ArrayList<>();
        boolean rateLimited = false;

        // Stage 1: Search from trusted skill markets
        for (SkillRegistry registry : registries) {
            if (!registry.isEnabled() || results.size() >= limit) {
                break;
            }

            // Skip remaining registry sources if rate limit detected
            if (rateLimited) {
                logger.info("Skipping registry due to rate limit: {}", registry.getName());
                continue;
            }

            try {
                List<SkillSearchResult> registryResults = searchFromRegistry(registry, query, limit - results.size());
                for (SkillSearchResult registryResult : registryResults) {
                    if (results.stream().noneMatch(r -> r.getFullName().equals(registryResult.getFullName())
                            && equalSubdir(r.getSkillSubdir(), registryResult.getSkillSubdir()))) {
                        results.add(registryResult);
                    }
                }
                logger.info("Registry search completed: registry={}, query={}, results={}",
                        registry.getName(), query, registryResults.size());
            } catch (GitHubRateLimitException e) {
                rateLimited = true;
                logger.warn("GitHub API rate limit hit, skipping remaining registries: registry={}, error={}",
                        registry.getName(), e.getMessage());
            } catch (Exception e) {
                logger.warn("Registry search failed: registry={}, error={}", registry.getName(), e.getMessage());
            }
        }

        // Stage 2: If global search enabled and results insufficient and not rate limited, fall back to GitHub global search
        if (allowGlobalSearch && results.size() < limit && !rateLimited) {
            try {
                List<SkillSearchResult> globalResults = searchGitHubGlobal(query, limit - results.size());
                for (SkillSearchResult globalResult : globalResults) {
                    if (results.stream().noneMatch(r -> r.getFullName().equals(globalResult.getFullName()))) {
                        globalResult.setTrusted(false);
                        results.add(globalResult);
                    }
                }
                logger.info("Global GitHub search completed: query={}, additional_results={}",
                        query, globalResults.size());
            } catch (GitHubRateLimitException e) {
                rateLimited = true;
                logger.warn("GitHub API rate limit hit during global search: error={}", e.getMessage());
            } catch (Exception e) {
                logger.warn("Global GitHub search failed: error={}", e.getMessage());
            }
        }

        // Limit final result count
        if (results.size() > limit) {
            results = new ArrayList<>(results.subList(0, limit));
        }

        // If rate limited resulted in no results, set flag for accurate messaging
        if (results.isEmpty() && rateLimited) {
            this.lastSearchRateLimited = true;
        } else {
            this.lastSearchRateLimited = false;
        }

        return results;
    }

    /**
     * Search from single trusted skill market
     *
     * Search strategy:
     * 1. Try to load registry.json index file and match keywords
     * 2. If no registry.json, scan repository root directory for subdirectories containing SKILL.md
     */
    private List<SkillSearchResult> searchFromRegistry(SkillRegistry registry, String query, int limit) throws Exception {
        List<SkillSearchResult> results = new ArrayList<>();

        // Try to load registry.json
        List<SkillSearchResult> indexResults = searchFromRegistryIndex(registry, query, limit);
        if (!indexResults.isEmpty()) {
            results.addAll(indexResults);
            return results;
        }

        // No registry.json, scan repository directory
        List<SkillSearchResult> dirResults = searchFromRepoDirectory(registry, query, limit);
        results.addAll(dirResults);

        return results;
    }

    /**
     * Search from registry.json index file
     *
     * Load registry.json file from trusted repository root, parse skill list,
     * and return results matching keywords.
     */
    private List<SkillSearchResult> searchFromRegistryIndex(SkillRegistry registry, String query, int limit) throws GitHubRateLimitException {
        List<SkillSearchResult> results = new ArrayList<>();

        try {
            String url = String.format(GITHUB_CONTENTS_API, registry.getRepo(), "registry.json");
            String responseBody = executeGitHubRequest(url);
            JsonNode fileNode = objectMapper.readTree(responseBody);

            // GitHub Contents API returns base64 encoded file content
            String content = fileNode.path("content").asText("");
            if (content.isEmpty()) {
                return results;
            }

            // Decode base64 content (GitHub returned content may contain newlines)
            String cleanContent = content.replaceAll("\\s", "");
            String decodedContent = new String(Base64.getDecoder().decode(cleanContent), StandardCharsets.UTF_8);

            RegistryIndex index = objectMapper.readValue(decodedContent, RegistryIndex.class);

            if (index.getSkills() != null) {
                for (RegistryIndex.SkillEntry entry : index.getSkills()) {
                    if (results.size() >= limit) {
                        break;
                    }
                    if (entry.matches(query)) {
                        SkillSearchResult result = new SkillSearchResult();
                        result.setFullName(entry.getRepo() != null ? entry.getRepo() : registry.getRepo());
                        result.setDescription(entry.getDescription());
                        result.setSkillSubdir(entry.getSubdir());
                        result.setHasSkillFile(true);
                        result.setTrusted(true);
                        result.setRegistrySource(registry.getName());
                        result.setUrl("https://github.com/" + result.getFullName());
                        if (entry.getName() != null) {
                            result.setSkillName(entry.getName());
                        }
                        results.add(result);
                    }
                }
            }
        } catch (GitHubRateLimitException e) {
            throw e;
        } catch (Exception e) {
            // registry.json does not exist or parse failed, ignore
            logger.debug("No registry.json found for {}: {}", registry.getRepo(), e.getMessage());
        }

        return results;
    }

    /**
     * Scan trusted repository directory for skills
     *
     * When repository has no registry.json, use GitHub Contents API
     * to list subdirectories in repository root, check if each contains SKILL.md.
     * Matching strategy:
     * 1. Match directory name against keywords first (fast match)
     * 2. If directory name doesn't match, try reading SKILL.md content for keyword matching (deep match)
     */
    private List<SkillSearchResult> searchFromRepoDirectory(SkillRegistry registry, String query, int limit) throws GitHubRateLimitException {
        List<SkillSearchResult> results = new ArrayList<>();

        try {
            String url = String.format(GITHUB_REPO_CONTENTS_API, registry.getRepo());
            String responseBody = executeGitHubRequest(url);
            JsonNode items = objectMapper.readTree(responseBody);

            if (!items.isArray()) {
                return results;
            }

            String lowerQuery = query.toLowerCase();
            String[] keywords = lowerQuery.split("\\s+");

            for (JsonNode item : items) {
                if (results.size() >= limit) {
                    break;
                }

                String type = item.path("type").asText("");
                String dirName = item.path("name").asText("");

                // Only process directories, skip hidden directories and special files
                if (!"dir".equals(type) || dirName.startsWith(".") || dirName.startsWith("_")) {
                    continue;
                }

                // Stage 1: Check if directory name matches any keyword (fast match)
                boolean nameMatches = false;
                for (String keyword : keywords) {
                    if (dirName.toLowerCase().contains(keyword)) {
                        nameMatches = true;
                        break;
                    }
                }

                if (nameMatches) {
                    // Directory name matched, verify SKILL.md exists
                    if (verifySkillFile(registry.getRepo(), dirName)) {
                        SkillSearchResult result = buildDirectoryResult(registry, dirName, null);
                        results.add(result);
                    }
                } else {
                    // Stage 2: Directory name didn't match, try reading SKILL.md content for deep match
                    String skillContent = fetchSkillFileContent(registry.getRepo(), dirName);
                    if (skillContent != null && matchesKeywords(skillContent, keywords)) {
                        SkillSearchResult result = buildDirectoryResult(registry, dirName, skillContent);
                        results.add(result);
                    }
                }
            }
        } catch (GitHubRateLimitException e) {
            throw e;
        } catch (Exception e) {
            logger.debug("Directory scan failed for {}: {}", registry.getRepo(), e.getMessage());
        }

        return results;
    }

    /**
     * Build search result from directory scan
     */
    private SkillSearchResult buildDirectoryResult(SkillRegistry registry, String dirName, String skillContent) {
        SkillSearchResult result = new SkillSearchResult();
        result.setFullName(registry.getRepo());
        result.setSkillSubdir(dirName);
        result.setSkillName(dirName);
        result.setHasSkillFile(true);
        result.setTrusted(true);
        result.setRegistrySource(registry.getName());
        result.setUrl("https://github.com/" + registry.getRepo() + "/tree/main/" + dirName);

        // Try to extract description from SKILL.md content (first non-empty non-heading line)
        if (skillContent != null) {
            String description = extractDescriptionFromSkillContent(skillContent);
            result.setDescription(description != null ? description : dirName);
        } else {
            result.setDescription(registry.getDescription() + " - " + dirName);
        }

        return result;
    }

    /**
     * Get SKILL.md file content
     *
     * @param repoFullName Repository full name
     * @param subdir       Subdirectory
     * @return SKILL.md text content, null if not exists
     */
    private String fetchSkillFileContent(String repoFullName, String subdir) {
        String path = (subdir != null && !subdir.isEmpty()) ? subdir + "/SKILL.md" : "SKILL.md";
        String url = String.format(GITHUB_CONTENTS_API, repoFullName, path);

        try {
            String responseBody = executeGitHubRequest(url);
            JsonNode fileNode = objectMapper.readTree(responseBody);
            String content = fileNode.path("content").asText("");
            if (content.isEmpty()) {
                return null;
            }
            String cleanContent = content.replaceAll("\\s", "");
            return new String(Base64.getDecoder().decode(cleanContent), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if text content matches any keyword
     */
    private boolean matchesKeywords(String content, String[] keywords) {
        String lowerContent = content.toLowerCase();
        for (String keyword : keywords) {
            if (lowerContent.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extract description from SKILL.md content
     *
     * Take first non-empty, non-Markdown heading line as description.
     */
    private String extractDescriptionFromSkillContent(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        String[] lines = content.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("---")) {
                return trimmed.length() > 120 ? trimmed.substring(0, 120) + "..." : trimmed;
            }
        }
        return null;
    }

    /**
     * GitHub global search (fallback strategy)
     *
     * When trusted market cannot find matching skills, if user has enabled allowGlobalSearch,
     * use GitHub Search API for global search. Results are marked as "unverified".
     */
    private List<SkillSearchResult> searchGitHubGlobal(String query, int limit) throws Exception {
        List<SkillSearchResult> results = new ArrayList<>();

        // Search repositories with tinyclaw-skill topic
        try {
            String searchQuery = query + " topic:tinyclaw-skill";
            String url = GITHUB_SEARCH_REPOS + "?q=" + encodeQuery(searchQuery)
                    + "&sort=stars&order=desc&per_page=" + limit;
            results.addAll(executeRepoSearch(url));
        } catch (Exception e) {
            logger.warn("Topic search failed: error={}", e.getMessage());
        }

        // Search repositories containing SKILL.md file
        if (results.size() < limit) {
            try {
                String searchQuery = query + " filename:SKILL.md";
                String url = GITHUB_SEARCH_CODE + "?q=" + encodeQuery(searchQuery)
                        + "&per_page=" + (limit - results.size());
                List<SkillSearchResult> codeResults = executeCodeSearch(url);
                for (SkillSearchResult codeResult : codeResults) {
                    if (results.stream().noneMatch(r -> r.getFullName().equals(codeResult.getFullName()))) {
                        results.add(codeResult);
                    }
                }
            } catch (Exception e) {
                logger.warn("Code search failed: error={}", e.getMessage());
            }
        }

        return results;
    }

    /**
     * Execute repository search API call and parse results
     */
    private List<SkillSearchResult> executeRepoSearch(String url) throws Exception {
        List<SkillSearchResult> results = new ArrayList<>();

        String responseBody = executeGitHubRequest(url);
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode items = root.path("items");

        if (items.isArray()) {
            for (JsonNode item : items) {
                SkillSearchResult result = parseRepoItem(item);
                if (result != null) {
                    results.add(result);
                }
            }
        }

        return results;
    }

    /**
     * Execute code search API call and parse results
     */
    private List<SkillSearchResult> executeCodeSearch(String url) throws Exception {
        List<SkillSearchResult> results = new ArrayList<>();

        String responseBody = executeGitHubRequest(url);
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode items = root.path("items");

        if (items.isArray()) {
            for (JsonNode item : items) {
                JsonNode repo = item.path("repository");
                if (!repo.isMissingNode()) {
                    SkillSearchResult result = parseRepoItem(repo);
                    if (result != null) {
                        result.setHasSkillFile(true);
                        String filePath = item.path("path").asText("");
                        if (!filePath.isEmpty() && !filePath.equals("SKILL.md")) {
                            String parentDir = filePath.substring(0, filePath.lastIndexOf('/'));
                            result.setSkillSubdir(parentDir);
                        }
                        results.add(result);
                    }
                }
            }
        }

        return results;
    }

    /**
     * Verify if repository contains SKILL.md file
     *
     * @param fullName Repository full name (owner/repo)
     * @param subdir   Subdirectory path (optional)
     * @return true if SKILL.md exists
     */
    public boolean verifySkillFile(String fullName, String subdir) {
        String path = (subdir != null && !subdir.isEmpty()) ? subdir + "/SKILL.md" : "SKILL.md";
        String url = String.format(GITHUB_CONTENTS_API, fullName, path);

        try {
            executeGitHubRequest(url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Parse repository JSON node to search result
     */
    private SkillSearchResult parseRepoItem(JsonNode item) {
        String fullName = item.path("full_name").asText("");
        if (fullName.isEmpty()) {
            return null;
        }

        SkillSearchResult result = new SkillSearchResult();
        result.setFullName(fullName);
        result.setDescription(item.path("description").asText(""));
        result.setStars(item.path("stargazers_count").asInt(0));
        result.setUrl(item.path("html_url").asText(""));
        result.setLanguage(item.path("language").asText(""));
        result.setUpdatedAt(item.path("updated_at").asText(""));

        JsonNode topics = item.path("topics");
        if (topics.isArray()) {
            List<String> topicList = new ArrayList<>();
            for (JsonNode topic : topics) {
                topicList.add(topic.asText());
            }
            result.setTopics(topicList);
            result.setHasSkillFile(topicList.contains("tinyclaw-skill"));
        }

        return result;
    }

    /**
     * Execute GitHub API request
     */
    private String executeGitHubRequest(String url) throws Exception {
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", USER_AGENT);

        if (githubToken != null && !githubToken.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + githubToken);
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (response.code() == 403) {
                String remaining = response.header("X-RateLimit-Remaining", "");
                if ("0".equals(remaining) || (response.body() != null
                        && response.peekBody(512).string().contains("rate limit"))) {
                    throw new GitHubRateLimitException(
                            "GitHub API rate limit exceeded. "
                                    + "Unauthenticated requests limited to 60/hour. "
                                    + "Configure GitHub Token in config to increase to 5000/hour.");
                }
                throw new Exception("GitHub API request denied: HTTP 403");
            }
            if (response.code() == 404) {
                throw new Exception("GitHub resource not found: " + url);
            }
            if (!response.isSuccessful()) {
                throw new Exception("GitHub API request failed: HTTP " + response.code());
            }

            return response.body() != null ? response.body().string() : "{}";
        }
    }

    private String encodeQuery(String query) {
        return URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    private boolean equalSubdir(String subdir1, String subdir2) {
        if (subdir1 == null && subdir2 == null) return true;
        if (subdir1 == null || subdir2 == null) return false;
        return subdir1.equals(subdir2);
    }

    /**
     * Get configured trusted skill market sources list
     *
     * @return Market sources list
     */
    public List<SkillRegistry> getRegistries() {
        return registries;
    }

    /**
     * Is GitHub global search enabled
     *
     * @return Whether global search is allowed
     */
    public boolean isAllowGlobalSearch() {
        return allowGlobalSearch;
    }

    /**
     * Format search results as human-readable string
     *
     * @param results Search result list
     * @param query   Original search keyword
     * @return Formatted results string
     */
    public String formatResults(List<SkillSearchResult> results, String query) {
        if (results.isEmpty()) {
            StringBuilder emptyMsg = new StringBuilder();
            emptyMsg.append("No skills found related to '").append(query).append("'.\n\n");

            if (this.lastSearchRateLimited) {
                emptyMsg.append("Warning: GitHub API rate limit exhausted\n");
                emptyMsg.append("Unauthenticated requests limited to 60/hour, quota exhausted.\n\n");
                emptyMsg.append("Solutions:\n");
                emptyMsg.append("- Configure GitHub Token to increase to 5000/hour\n");
                emptyMsg.append("- Wait ~1 hour for limit to reset\n");
                emptyMsg.append("- Manually install known repository: skills(action='install', repo='owner/repo')\n");
                emptyMsg.append("- Create your own skill: skills(action='create', ...)");
            } else {
                emptyMsg.append("Possible reasons:\n");
                emptyMsg.append("- Keywords don't match skill directory names or SKILL.md content, try English or more general terms\n");
                emptyMsg.append("- Trusted market source repositories may be temporarily inaccessible\n\n");
                emptyMsg.append("Suggestions:\n");
                emptyMsg.append("- Try different keywords, e.g.: skills(action='search', query='code review')\n");
                emptyMsg.append("- Configure GitHub Token to increase API rate limits\n");
                emptyMsg.append("- Enable global search to expand scope (set tools.skills.allowGlobalSearch=true)\n");
                emptyMsg.append("- Manually install known repository: skills(action='install', repo='owner/repo')\n");
                emptyMsg.append("- Create your own skill: skills(action='create', ...)");
            }
            return emptyMsg.toString();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" skill(s) related to '").append(query).append("':\n\n");

        for (int i = 0; i < results.size(); i++) {
            SkillSearchResult result = results.get(i);
            sb.append(i + 1).append(". ");

            // Display skill name or repository name
            if (result.getSkillName() != null && !result.getSkillName().isEmpty()) {
                sb.append("**").append(result.getSkillName()).append("**");
                sb.append(" (").append(result.getFullName()).append(")");
            } else {
                sb.append("**").append(result.getFullName()).append("**");
            }

            if (result.getStars() > 0) {
                sb.append(" [Stars: ").append(result.getStars()).append("]");
            }
            sb.append("\n");

            if (result.getDescription() != null && !result.getDescription().isEmpty()) {
                sb.append("   ").append(result.getDescription()).append("\n");
            }

            // Source marker
            if (result.isTrusted()) {
                sb.append("   [Trusted] Source: ").append(result.getRegistrySource() != null
                        ? result.getRegistrySource() : "Trusted Market").append("\n");
            } else {
                sb.append("   [Unverified] Source: GitHub Global Search (please verify security)\n");
            }

            if (result.isHasSkillFile()) {
                sb.append("   [OK] Contains SKILL.md\n");
            }

            String installCmd = result.getInstallSpecifier();
            sb.append("   Install: skills(action='install', repo='").append(installCmd).append("')\n");
            sb.append("\n");
        }

        sb.append("---\n");
        sb.append("Direct install: skills(action='install', repo='owner/repo')\n");
        sb.append("Search and install: skills(action='search_install', query='").append(query).append("')");

        return sb.toString();
    }

    /**
     * GitHub API rate limit exception
     */
    public static class GitHubRateLimitException extends Exception {

        public GitHubRateLimitException(String message) {
            super(message);
        }
    }

    /**
     * Skill market index
     * <p>
     * Corresponds to top-level structure of registry.json.
     */
    public static class RegistryIndex {

        private String name;
        private String description;
        private List<SkillEntry> skills;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<SkillEntry> getSkills() {
            return skills;
        }

        public void setSkills(List<SkillEntry> skills) {
            this.skills = skills;
        }

        /**
         * Skill entry in skill market
         * <p>
         * Corresponds to each skill's metadata in registry.json.
         */
        public static class SkillEntry {

            private String name;
            private String description;
            private String repo;
            private String subdir;
            private List<String> tags;
            private String author;

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getDescription() {
                return description;
            }

            public void setDescription(String description) {
                this.description = description;
            }

            public String getRepo() {
                return repo;
            }

            public void setRepo(String repo) {
                this.repo = repo;
            }

            public String getSubdir() {
                return subdir;
            }

            public void setSubdir(String subdir) {
                this.subdir = subdir;
            }

            public List<String> getTags() {
                return tags;
            }

            public void setTags(List<String> tags) {
                this.tags = tags;
            }

            public String getAuthor() {
                return author;
            }

            public void setAuthor(String author) {
                this.author = author;
            }

            /**
     * Get install specifier
     */
    public String getInstallSpecifier() {
        if (subdir != null && !subdir.isEmpty()) {
            return repo + "/" + subdir;
        }
        return repo;
    }

            /**
             * Check if skill matches search keywords
             * <p>
             * Case-insensitive matching in name, description, and tags.
             *
             * @param query Search keyword
             * @return Whether matches
             */
            public boolean matches(String query) {
                if (query == null || query.isEmpty()) {
                    return true;
                }
                String lowerQuery = query.toLowerCase();
                String[] keywords = lowerQuery.split("\\s+");

                for (String keyword : keywords) {
                    boolean found = false;

                    if (name != null && name.toLowerCase().contains(keyword)) {
                        found = true;
                    }
                    if (!found && description != null && description.toLowerCase().contains(keyword)) {
                        found = true;
                    }
                    if (!found && tags != null) {
                        for (String tag : tags) {
                            if (tag.toLowerCase().contains(keyword)) {
                                found = true;
                                break;
                            }
                        }
                    }
                    if (!found && author != null && author.toLowerCase().contains(keyword)) {
                        found = true;
                    }

                    if (!found) {
                        return false;
                    }
                }
                return true;
            }
        }
    }
}
