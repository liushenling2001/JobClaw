package io.jobclaw.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Skill Loader - Load and manage Agent skills
 *
 * Load skill files from multiple sources, supporting workspace, global, and builtin levels.
 * Skill files are in Markdown format with YAML frontmatter defining name and description.
 *
 * Priority: workspace > global > builtin
 */
public class SkillsLoader {

    private static final Logger logger = LoggerFactory.getLogger(SkillsLoader.class);

    /**
     * Workspace root path
     */
    private final String workspace;

    /**
     * Workspace skills directory path (workspace/skills)
     */
    private final String workspaceSkills;

    /**
     * Global skills directory path
     */
    private final String globalSkills;

    /**
     * Built-in skill names list (loaded from classpath)
     */
    private static final List<String> BUILTIN_SKILL_NAMES = Arrays.asList(
            "weather", "github", "summarize", "skill-creator", "tmux", "work-partner-skill-creator",
            "experience-review"
    );

    /**
     * Base path for built-in skills in classpath
     */
    private static final String BUILTIN_SKILLS_PATH = "skills/";

    /**
     * Create SkillsLoader instance
     *
     * @param workspace     Workspace root path
     * @param globalSkills  Global skills directory path
     * @param builtinSkills Built-in skills directory path (deprecated, built-in skills loaded from classpath)
     */
    public SkillsLoader(String workspace, String globalSkills, String builtinSkills) {
        this.workspace = workspace;
        this.workspaceSkills = Paths.get(workspace, "skills").toString();
        this.globalSkills = globalSkills;
    }

    /**
     * List all available skills
     *
     * Load by priority, higher priority overwrites lower priority for same name
     */
    public List<SkillInfo> listSkills() {
        List<SkillInfo> skills = new ArrayList<>();

        // Workspace skills (highest priority)
        if (workspaceSkills != null) {
            addSkillsFromDir(skills, workspaceSkills, "workspace");
        }

        // Global skills
        if (globalSkills != null) {
            addSkillsFromDir(skills, globalSkills, "global");
        }

        // Built-in skills (loaded from classpath)
        addBuiltinSkills(skills);

        return skills;
    }

    /**
     * Add built-in skills from classpath
     *
     * @param skills Skills list
     */
    private void addBuiltinSkills(List<SkillInfo> skills) {
        for (String skillName : BUILTIN_SKILL_NAMES) {
            // Check if higher priority skill with same name exists
            boolean exists = skills.stream()
                    .anyMatch(s -> s.getName().equals(skillName));

            if (!exists) {
                String content = loadBuiltinSkillContent(skillName);
                if (content != null) {
                    SkillInfo info = new SkillInfo();
                    info.setName(skillName);
                    info.setPath("classpath:" + BUILTIN_SKILLS_PATH + skillName + "/SKILL.md");
                    info.setSource("builtin");

                    // Parse metadata
                    String frontmatter = extractFrontmatter(content);
                    if (frontmatter != null && !frontmatter.isEmpty()) {
                        Map<String, String> yaml = parseSimpleYAML(frontmatter);
                        info.setDescription(yaml.getOrDefault("description", ""));
                    }

                    skills.add(info);
                }
            }
        }
    }

    /**
     * Load built-in skill content from classpath
     *
     * @param skillName Skill name
     * @return Skill content, null on failure
     */
    private String loadBuiltinSkillContent(String skillName) {
        String resourcePath = BUILTIN_SKILLS_PATH + skillName + "/SKILL.md";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Add skills from specified directory to list
     */
    private void addSkillsFromDir(List<SkillInfo> skills, String dirPath, String source) {
        Path dir = Paths.get(dirPath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return;

        try {
            Files.list(dir).filter(Files::isDirectory).forEach(skillDir -> {
                String name = skillDir.getFileName().toString();
                Path skillFile = skillDir.resolve("SKILL.md");

                if (Files.exists(skillFile)) {
                    // Check if higher priority skill with same name exists
                    boolean exists = skills.stream()
                            .anyMatch(s -> s.getName().equals(name) &&
                                    (source.equals("builtin") ||
                                            (source.equals("global") && s.getSource().equals("workspace"))));

                    if (!exists) {
                        SkillInfo info = new SkillInfo();
                        info.setName(name);
                        info.setPath(skillFile.toString());
                        info.setSource(source);

                        // Load description
                        String description = parseSkillDescription(skillFile);
                        if (description != null) {
                            info.setDescription(description);
                        }

                        skills.add(info);
                    }
                }
            });
        } catch (IOException e) {
            // Ignore read errors
        }
    }

    /**
     * Load skill by name, return content without YAML frontmatter
     */
    public String loadSkill(String name) {
        // Try workspace skills first
        String content = loadSkillFromDir(workspaceSkills, name);
        if (content != null) return content;

        // Try global skills
        content = loadSkillFromDir(globalSkills, name);
        if (content != null) return content;

        // Try built-in skills (loaded from classpath)
        content = loadBuiltinSkillContent(name);
        if (content != null) {
            return stripFrontmatter(content);
        }
        return null;
    }

    /**
     * Load skill from specified directory
     */
    private String loadSkillFromDir(String dir, String name) {
        if (dir == null) return null;

        Path skillFile = Paths.get(dir, name, "SKILL.md");
        if (Files.exists(skillFile)) {
            try {
                String content = Files.readString(skillFile);
                return stripFrontmatter(content);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Build skill summary (XML format)
     */
    public String buildSkillsSummary() {
        List<SkillInfo> allSkills = listSkills();
        if (allSkills.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("<skills>\n");

        for (SkillInfo s : allSkills) {
            sb.append("  <skill>\n");
            sb.append("    <name>").append(escapeXML(s.getName())).append("</name>\n");
            sb.append("    <description>").append(escapeXML(s.getDescription())).append("</description>\n");
            sb.append("    <location>").append(escapeXML(s.getPath())).append("</location>\n");
            sb.append("    <source>").append(s.getSource()).append("</source>\n");
            sb.append("  </skill>\n");
        }

        sb.append("</skills>");
        return sb.toString();
    }

    /**
     * Parse description from SKILL.md YAML frontmatter
     */
    private String parseSkillDescription(Path skillPath) {
        try {
            String content = Files.readString(skillPath);
            String frontmatter = extractFrontmatter(content);
            if (frontmatter == null || frontmatter.isEmpty()) {
                return "";
            }
            Map<String, String> yaml = parseSimpleYAML(frontmatter);
            return yaml.getOrDefault("description", "");
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Extract YAML frontmatter (content between --- separators)
     */
    private String extractFrontmatter(String content) {
        Pattern pattern = Pattern.compile("(?s)^---\n(.*)\n---");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    /**
     * Remove YAML frontmatter
     */
    private String stripFrontmatter(String content) {
        return content.replaceFirst("^---\n.*?\n---\n", "");
    }

    /**
     * Parse simple YAML format (key: value)
     */
    private Map<String, String> parseSimpleYAML(String content) {
        Map<String, String> result = new HashMap<>();

        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                // Remove quotes
                value = value.replaceAll("^['\"]|['\"]$", "");
                result.put(key, value);
            }
        }

        return result;
    }

    /**
     * Extract builtin skill from classpath to filesystem cache directory.
     *
     * @param skillName Skill name
     * @return Extracted filesystem path, null on failure
     */
    public String extractBuiltinSkillToFileSystem(String skillName) {
        if (!BUILTIN_SKILL_NAMES.contains(skillName)) {
            return null;
        }

        Path cacheDir = Paths.get(workspace, ".builtin-cache", skillName);
        Path cachedSkillFile = cacheDir.resolve("SKILL.md");

        // If cache exists, return it
        if (Files.exists(cachedSkillFile)) {
            return cacheDir.toAbsolutePath().toString();
        }

        // Extract from classpath to cache directory
        String resourceBase = BUILTIN_SKILLS_PATH + skillName + "/";
        try {
            Files.createDirectories(cacheDir);

            // Copy SKILL.md
            String skillContent = loadBuiltinSkillContent(skillName);
            if (skillContent == null) {
                return null;
            }
            Files.writeString(cachedSkillFile, skillContent);

            // Try to copy other resources in skill directory (scripts/, references/, assets/)
            for (String subdir : new String[]{"scripts", "references", "assets"}) {
                extractBuiltinSubdirectory(resourceBase + subdir + "/", cacheDir.resolve(subdir));
            }

            return cacheDir.toAbsolutePath().toString();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Try to extract subdirectory resources from classpath.
     *
     * @param resourcePath Resource path prefix in classpath
     * @param targetDir    Target filesystem directory
     */
    private void extractBuiltinSubdirectory(String resourcePath, Path targetDir) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is != null) {
                Files.createDirectories(targetDir);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String fileName;
                    while ((fileName = reader.readLine()) != null) {
                        fileName = fileName.trim();
                        if (fileName.isEmpty()) continue;
                        try (InputStream fileIs = getClass().getClassLoader()
                                .getResourceAsStream(resourcePath + fileName)) {
                            if (fileIs != null) {
                                Files.copy(fileIs, targetDir.resolve(fileName));
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            // Subdirectory does not exist or cannot be read, ignore
        }
    }

    /**
     * Delete workspace skill (delete directory)
     *
     * @param name Skill name
     * @return true if successful, false if skill does not exist or is not workspace skill
     */
    public boolean deleteWorkspaceSkill(String name) {
        if (workspaceSkills == null) return false;
        Path skillDir = Paths.get(workspaceSkills, name);
        if (!Files.exists(skillDir) || !Files.isDirectory(skillDir)) return false;
        try {
            deleteDirectoryRecursively(skillDir);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Recursively delete directory
     */
    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.toList()) {
                    deleteDirectoryRecursively(entry);
                }
            }
        }
        Files.delete(path);
    }

    /**
     * Save (create or update) workspace skill content
     *
     * @param name    Skill name
     * @param content Skill file full content
     * @return true if successful
     */
    public boolean saveWorkspaceSkill(String name, String content) {
        if (workspaceSkills == null) return false;
        Path skillDir = Paths.get(workspaceSkills, name);
        Path skillFile = skillDir.resolve("SKILL.md");
        try {
            Files.createDirectories(skillDir);
            Files.writeString(skillFile, content, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Escape XML special characters
     */
    private String escapeXML(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
