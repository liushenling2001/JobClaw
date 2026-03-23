package io.jobclaw.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 定义 - 支持动态创建 Agent 角色
 * 
 * 相比 enum 方式的 AgentRole，AgentDefinition 支持：
 * - 运行时动态创建
 * - 自定义工具集
 * - 自定义技能集
 * - 灵活配置
 */
public class AgentDefinition {

    private final String code;
    private final String displayName;
    private final String systemPrompt;
    private final List<String> allowedTools;
    private final List<String> allowedSkills;
    private final Map<String, Object> metadata;

    /**
     * 创建 Agent 定义
     * 
     * @param code 角色代码（唯一标识）
     * @param displayName 显示名称
     * @param systemPrompt 系统提示词
     * @param allowedTools 允许使用的工具列表（null 表示不限制）
     * @param allowedSkills 允许使用的技能列表（null 表示不限制）
     */
    public AgentDefinition(String code, String displayName, String systemPrompt,
                          List<String> allowedTools, List<String> allowedSkills) {
        this.code = code;
        this.displayName = displayName;
        this.systemPrompt = systemPrompt;
        this.allowedTools = allowedTools != null ? new ArrayList<>(allowedTools) : null;
        this.allowedSkills = allowedSkills != null ? new ArrayList<>(allowedSkills) : null;
        this.metadata = new HashMap<>();
    }

    /**
     * 从 AgentRole 枚举创建定义
     */
    public static AgentDefinition fromRole(AgentRole role) {
        return new AgentDefinition(
            role.getCode(),
            role.getDisplayName(),
            role.getSystemPrompt(),
            role.getAllowedTools(),
            null // Skills 暂不设置
        );
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public List<String> getAllowedTools() {
        return allowedTools;
    }

    public List<String> getAllowedSkills() {
        return allowedSkills;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 检查是否允许使用某个工具
     */
    public boolean isToolAllowed(String toolName) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return true; // 没有限制，允许所有工具
        }
        return allowedTools.contains(toolName);
    }

    /**
     * 检查是否允许使用某个技能
     */
    public boolean isSkillAllowed(String skillName) {
        if (allowedSkills == null || allowedSkills.isEmpty()) {
            return true; // 没有限制，允许所有技能
        }
        return allowedSkills.contains(skillName);
    }

    /**
     * 添加元数据
     */
    public void putMetadata(String key, Object value) {
        this.metadata.put(key, value);
    }

    /**
     * 构建器模式
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String code;
        private String displayName;
        private String systemPrompt;
        private List<String> allowedTools;
        private List<String> allowedSkills;

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder allowedTools(List<String> allowedTools) {
            this.allowedTools = allowedTools;
            return this;
        }

        public Builder allowedSkills(List<String> allowedSkills) {
            this.allowedSkills = allowedSkills;
            return this;
        }

        public AgentDefinition build() {
            return new AgentDefinition(code, displayName, systemPrompt, allowedTools, allowedSkills);
        }
    }

    @Override
    public String toString() {
        return "AgentDefinition{" +
                "code='" + code + '\'' +
                ", displayName='" + displayName + '\'' +
                ", allowedTools=" + allowedTools +
                ", allowedSkills=" + allowedSkills +
                '}';
    }
}
