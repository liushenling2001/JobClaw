package io.jobclaw.agent.catalog;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AgentIntentRouter {

    public enum IntentType {
        DEFAULT_CHAT,
        CREATE_AGENT,
        INVOKE_AGENT
    }

    public record AgentIntent(IntentType type, String agentName, String task, boolean asyncRequested) {
    }

    private static final Pattern CREATE_PATTERN = Pattern.compile(
            "(?:帮我)?(?:创建|新建|建立|做一个|生成一个)(.+?)(?:智能体|agent)(.*)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern INVOKE_PATTERN = Pattern.compile(
            "(?:使用|调用|让)(.+?)(?:智能体|agent)?[，,:：\\s]+(.+)",
            Pattern.CASE_INSENSITIVE
    );

    public AgentIntent route(String userContent) {
        if (userContent == null || userContent.isBlank()) {
            return new AgentIntent(IntentType.DEFAULT_CHAT, null, null, false);
        }

        Matcher createMatcher = CREATE_PATTERN.matcher(userContent.trim());
        if (createMatcher.find()) {
            String rawName = normalizeName(createMatcher.group(1));
            String tail = createMatcher.group(2) != null ? createMatcher.group(2).trim() : "";
            return new AgentIntent(IntentType.CREATE_AGENT, rawName, tail, false);
        }

        Matcher invokeMatcher = INVOKE_PATTERN.matcher(userContent.trim());
        if (invokeMatcher.find()) {
            String rawName = normalizeName(invokeMatcher.group(1));
            String task = invokeMatcher.group(2) != null ? invokeMatcher.group(2).trim() : "";
            boolean async = userContent.contains("后台") || userContent.toLowerCase().contains("async");
            return new AgentIntent(IntentType.INVOKE_AGENT, rawName, task, async);
        }

        return new AgentIntent(IntentType.DEFAULT_CHAT, null, null, false);
    }

    private String normalizeName(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replace("一个", "")
                .replace("专门", "")
                .replace("专用", "")
                .replace("负责", "")
                .trim();
    }
}
