package io.jobclaw.skills;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill Registry - Defines trusted skill index repositories
 *
 * Each SkillRegistry represents a trusted skill market source, similar to Homebrew's tap concept.
 * A skill market source is a GitHub repository containing a registry.json index file in its root,
 * listing metadata for all available skills in that repository.
 */
public class SkillRegistry {

    private String name;
    private String repo;
    private String description;
    private boolean enabled;

    public SkillRegistry() {
        this.enabled = true;
    }

    public SkillRegistry(String name, String repo, String description) {
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

    /**
     * Get built-in default skill market sources
     *
     * @return Default skill market sources list
     */
    public static List<SkillRegistry> getDefaultRegistries() {
        List<SkillRegistry> registries = new ArrayList<>();

        registries.add(new SkillRegistry(
                "TinyClaw Official",
                "leavesfly/tinyclaw-skills",
                "Official skill collection with common utility skills"
        ));

        registries.add(new SkillRegistry(
                "VoltAgent Skills",
                "VoltAgent/awesome-agent-skills",
                "500+ agent skills, compatible with Claude Code / Codex / Gemini CLI"
        ));

        registries.add(new SkillRegistry(
                "Composio Skills",
                "ComposioHQ/awesome-claude-skills",
                "Composio community curated Claude skills covering productivity tools"
        ));

        registries.add(new SkillRegistry(
                "Travis Skills",
                "travisvn/awesome-claude-skills",
                "Community curated Claude Skills with rich skills and tutorials"
        ));

        registries.add(new SkillRegistry(
                "Jeffallan Skills",
                "Jeffallan/claude-skills",
                "66+ professional full-stack development skills"
        ));

        return registries;
    }
}
