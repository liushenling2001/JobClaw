package io.jobclaw.skills;

import io.jobclaw.config.ToolsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * Skills Service - Manages skill loading, installation, and searching
 *
 * Spring component that wraps SkillsLoader, SkillsInstaller, and SkillsSearcher
 * to provide unified skill management capabilities.
 */
@Component
public class SkillsService {

    private static final Logger logger = LoggerFactory.getLogger(SkillsService.class);

    private final String workspace;
    private final ToolsConfig.SkillsToolConfig skillsConfig;

    private SkillsLoader skillsLoader;
    private SkillsInstaller skillsInstaller;
    private SkillsSearcher skillsSearcher;

    public SkillsService(
            io.jobclaw.config.Config config,
            ToolsConfig toolsConfig) {
        this.workspace = config.getWorkspacePath();
        this.skillsConfig = toolsConfig != null ? toolsConfig.getSkills() : new ToolsConfig.SkillsToolConfig();
    }

    @PostConstruct
    public void init() {
        this.skillsLoader = new SkillsLoader(workspace, null, null);
        this.skillsInstaller = new SkillsInstaller(workspace);
        this.skillsSearcher = SkillsSearcher.fromConfig(skillsConfig);

        logger.info("Skills service initialized, workspace: {}", workspace);
    }

    /**
     * List all available skills
     *
     * @return List of skill information
     */
    public List<SkillInfo> listSkills() {
        return skillsLoader.listSkills();
    }

    /**
     * Load skill content by name
     *
     * @param name Skill name
     * @return Skill content without frontmatter, null if not found
     */
    public String loadSkill(String name) {
        return skillsLoader.loadSkill(name);
    }

    /**
     * Build skills summary in XML format
     *
     * @return XML formatted skills summary
     */
    public String buildSkillsSummary() {
        return skillsLoader.buildSkillsSummary();
    }

    /**
     * Install skill from GitHub repository
     *
     * @param repoSpecifier Repository specifier (owner/repo or owner/repo/subdir)
     * @return Installation result message
     * @throws Exception Installation errors
     */
    public String installSkill(String repoSpecifier) throws Exception {
        return skillsInstaller.install(repoSpecifier);
    }

    /**
     * Delete workspace skill
     *
     * @param name Skill name
     * @return true if successful
     */
    public boolean deleteSkill(String name) {
        return skillsLoader.deleteWorkspaceSkill(name);
    }

    /**
     * Save (create or update) workspace skill
     *
     * @param name    Skill name
     * @param content Skill file content
     * @return true if successful
     */
    public boolean saveSkill(String name, String content) {
        return skillsLoader.saveWorkspaceSkill(name, content);
    }

    /**
     * Search for skills
     *
     * @param query      Search keyword
     * @param maxResults Maximum results to return
     * @return Search results list
     */
    public List<SkillSearchResult> searchSkills(String query, int maxResults) {
        return skillsSearcher.search(query, maxResults);
    }

    /**
     * Search for skills with default max results
     *
     * @param query Search keyword
     * @return Search results list
     */
    public List<SkillSearchResult> searchSkills(String query) {
        return searchSkills(query, 5);
    }

    /**
     * Format search results as human-readable string
     *
     * @param results Search results
     * @param query   Original search keyword
     * @return Formatted results string
     */
    public String formatSearchResults(List<SkillSearchResult> results, String query) {
        return skillsSearcher.formatResults(results, query);
    }

    /**
     * Get the skills loader
     *
     * @return SkillsLoader instance
     */
    public SkillsLoader getLoader() {
        return skillsLoader;
    }

    /**
     * Get the skills installer
     *
     * @return SkillsInstaller instance
     */
    public SkillsInstaller getInstaller() {
        return skillsInstaller;
    }

    /**
     * Get the skills searcher
     *
     * @return SkillsSearcher instance
     */
    public SkillsSearcher getSearcher() {
        return skillsSearcher;
    }
}
