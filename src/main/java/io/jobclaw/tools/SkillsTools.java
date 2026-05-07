package io.jobclaw.tools;

import io.jobclaw.config.Config;
import io.jobclaw.skills.SkillsService;
import io.jobclaw.skills.SkillInfo;
import io.jobclaw.skills.SkillSearchResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 技能管理工具 - 基于 Spring AI @Tool 注解
 * 
 * 提供技能的列表、调用、安装、创建、编辑、删除等功能
 */
@Component
public class SkillsTools {

    private final SkillsService skillsService;
    private final String workspace;

    public SkillsTools(SkillsService skillsService, Config config) {
        this.skillsService = skillsService;
        this.workspace = config.getWorkspacePath();
    }

    @Tool(name = "skills", description = "Manage and execute skills. Supports operations: list, invoke, install, create, edit, remove, search. Use 'list' to see all available skills, 'invoke' to execute a skill, 'install' to install from GitHub, 'create/edit/remove' to manage workspace skills, 'search' to find skills by keyword.")
    public String execute(
        @ToolParam(description = "Operation: list/invoke/install/create/edit/remove/search") String action,
        @ToolParam(description = "Skill name (required for invoke/edit/remove)", required = false) String name,
        @ToolParam(description = "Skill content (required for create/edit)", required = false) String content,
        @ToolParam(description = "Skill description (required for create)", required = false) String description,
        @ToolParam(description = "GitHub repository (required for install, format: owner/repo or owner/repo/subdir)", required = false) String repo,
        @ToolParam(description = "Search keyword (required for search)", required = false) String query
    ) {
        if (action == null || action.isEmpty()) {
            return "Error: action is required (list/invoke/install/create/edit/remove/search)";
        }

        try {
            return switch (action.toLowerCase()) {
                case "list" -> executeList();
                case "invoke" -> executeInvoke(name);
                case "install" -> executeInstall(repo);
                case "create" -> executeCreate(name, content, description);
                case "edit" -> executeEdit(name, content);
                case "remove" -> executeRemove(name);
                case "search" -> executeSearch(query);
                default -> "Error: unknown action '" + action + "'. Valid actions: list, invoke, install, create, edit, remove, search";
            };
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * List all available skills
     */
    private String executeList() {
        List<SkillInfo> skills = skillsService.listSkills();
        if (skills.isEmpty()) {
            return "No skills available.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Available skills:\n\n");
        for (SkillInfo skill : skills) {
            sb.append("- **").append(skill.getName()).append("**: ")
              .append(skill.getDescription() != null ? skill.getDescription() : "No description")
              .append(" [").append(skill.getSource()).append("]\n");
        }
        return sb.toString();
    }

    /**
     * Invoke a skill by name
     */
    private String executeInvoke(String name) {
        if (name == null || name.isEmpty()) {
            return "Error: name is required for invoke operation";
        }

        // Load skill content
        String content = skillsService.loadSkill(name);
        if (content == null) {
            return "Error: skill '" + name + "' not found";
        }

        // Find skill location and get base-path
        String basePath = findSkillBasePath(name);

        // Return formatted result with base-path for script execution
        StringBuilder sb = new StringBuilder();
        sb.append("<skill-invocation>\n");
        sb.append("<name>").append(name).append("</name>\n");
        sb.append("<base-path>").append(basePath).append("</base-path>\n");
        sb.append("</skill-invocation>\n\n");
        sb.append("# Skill: ").append(name).append("\n\n");
        sb.append(content);
        sb.append("\n\n---\n\n");
        sb.append("**技能运行约束**:\n");
        sb.append("- 严格遵循上述技能说明；如果技能声明了脚本或命令入口，请把 base-path 作为工作目录执行该入口。\n");
        sb.append("- 运行技能时不要自行读取、分析或修改技能内部实现文件；只有当用户明确要求创建、编辑、调试或修复技能时才可以检查或改动技能文件。\n");
        sb.append("- 不要通过 shell 读取 `.env`、密钥、令牌、凭据文件；需要配置时根据技能说明使用环境变量或让用户在安全位置配置。\n");
        sb.append("- 长时间任务应在 run_command/exec 调用中设置合理 timeout，避免外层工具超时中断。");

        return sb.toString();
    }

    /**
     * Find skill base-path for script execution
     */
    private String findSkillBasePath(String skillName) {
        // Try workspace skills first
        java.nio.file.Path workspaceSkill = java.nio.file.Paths.get(workspace, "skills", skillName);
        if (java.nio.file.Files.exists(workspaceSkill)) {
            return workspaceSkill.toAbsolutePath().toString();
        }

        // Try to extract builtin skill to filesystem
        String extractedPath = skillsService.getLoader().extractBuiltinSkillToFileSystem(skillName);
        if (extractedPath != null) {
            return extractedPath;
        }

        // Fallback: return classpath reference (may not work for script execution)
        return "classpath:skills/" + skillName;
    }

    /**
     * Install skill from GitHub repository
     */
    private String executeInstall(String repo) {
        if (repo == null || repo.isEmpty()) {
            return "Error: repo is required for install operation (format: owner/repo or owner/repo/subdir)";
        }

        try {
            String result = skillsService.installSkill(repo);
            return "Successfully installed skill: " + result;
        } catch (Exception e) {
            return "Error installing skill: " + e.getMessage();
        }
    }

    /**
     * Create a new workspace skill
     */
    private String executeCreate(String name, String content, String description) {
        if (name == null || name.isEmpty()) {
            return "Error: name is required for create operation";
        }
        if (content == null) {
            return "Error: content is required for create operation";
        }

        // Build skill file with frontmatter
        StringBuilder skillContent = new StringBuilder();
        skillContent.append("---\n");
        skillContent.append("name: ").append(name).append("\n");
        if (description != null && !description.isEmpty()) {
            skillContent.append("description: ").append(description).append("\n");
        }
        skillContent.append("---\n\n");
        skillContent.append(content);

        boolean success = skillsService.saveSkill(name, skillContent.toString());
        if (success) {
            return "Successfully created skill: " + name;
        } else {
            return "Error: failed to create skill '" + name + "'";
        }
    }

    /**
     * Edit an existing workspace skill
     */
    private String executeEdit(String name, String content) {
        if (name == null || name.isEmpty()) {
            return "Error: name is required for edit operation";
        }
        if (content == null) {
            return "Error: content is required for edit operation";
        }

        // Load existing skill to preserve frontmatter
        String existingContent = skillsService.loadSkill(name);
        if (existingContent == null) {
            return "Error: skill '" + name + "' not found";
        }

        // For simplicity, replace content while keeping frontmatter
        // In a real scenario, you might want more sophisticated editing
        StringBuilder skillContent = new StringBuilder();
        skillContent.append("---\n");
        skillContent.append("name: ").append(name).append("\n");
        skillContent.append("description: Updated skill\n");
        skillContent.append("---\n\n");
        skillContent.append(content);

        boolean success = skillsService.saveSkill(name, skillContent.toString());
        if (success) {
            return "Successfully edited skill: " + name;
        } else {
            return "Error: failed to edit skill '" + name + "'";
        }
    }

    /**
     * Remove a workspace skill
     */
    private String executeRemove(String name) {
        if (name == null || name.isEmpty()) {
            return "Error: name is required for remove operation";
        }

        boolean success = skillsService.deleteSkill(name);
        if (success) {
            return "Successfully removed skill: " + name;
        } else {
            return "Error: failed to remove skill '" + name + "' (may not exist or is not a workspace skill)";
        }
    }

    /**
     * Search for skills by keyword
     */
    private String executeSearch(String query) {
        if (query == null || query.isEmpty()) {
            return "Error: query is required for search operation";
        }

        List<SkillSearchResult> results = skillsService.searchSkills(query);
        if (results.isEmpty()) {
            return "No skills found matching: " + query;
        }

        return skillsService.formatSearchResults(results, query);
    }
}
