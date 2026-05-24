package io.jobclaw.skills;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Selects a small set of relevant installed skills for the current task.
 *
 * This keeps the system prompt focused: full skill content is still loaded with
 * the skills tool, while the prompt only receives relevant skill summaries.
 */
public class SkillSelectionPolicy {

    private static final int DEFAULT_LIMIT = 5;
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "that", "this", "from", "into", "what", "how",
            "一个", "这个", "那个", "需要", "进行", "处理", "帮我", "请你", "所有", "全部"
    );

    public List<SkillInfo> selectInstalledSkills(List<SkillInfo> skills, String userInput) {
        return selectInstalledSkills(skills, userInput, DEFAULT_LIMIT);
    }

    public List<SkillInfo> selectInstalledSkills(List<SkillInfo> skills, String userInput, int limit) {
        if (skills == null || skills.isEmpty() || userInput == null || userInput.isBlank()) {
            return List.of();
        }
        Set<String> queryTerms = extractTerms(userInput);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        int safeLimit = limit > 0 ? limit : DEFAULT_LIMIT;
        return skills.stream()
                .map(skill -> new ScoredSkill(skill, score(skill, queryTerms)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredSkill::score).reversed()
                        .thenComparing(scored -> scored.skill().getName()))
                .limit(safeLimit)
                .map(ScoredSkill::skill)
                .toList();
    }

    private int score(SkillInfo skill, Set<String> queryTerms) {
        String name = normalize(skill.getName());
        String description = normalize(skill.getDescription());
        int score = 0;
        for (String term : queryTerms) {
            if (name.contains(term)) {
                score += 4;
            }
            if (description.contains(term)) {
                score += 2;
            }
        }
        return score;
    }

    private Set<String> extractTerms(String text) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String normalized = normalize(text);
        for (String token : normalized.split("[\\s,，。！？!?;；:：、()（）\\[\\]{}\"'`/\\\\]+")) {
            String term = token.trim();
            if (term.length() < 2 || STOP_WORDS.contains(term)) {
                continue;
            }
            terms.add(term);
        }

        // Add compact Chinese hints for common task families where whitespace tokenization is weak.
        addIfPresent(terms, normalized, "总结");
        addIfPresent(terms, normalized, "摘要");
        addIfPresent(terms, normalized, "报告");
        if (normalized.contains("总结") || normalized.contains("摘要") || normalized.contains("报告")) {
            terms.add("summarize");
            terms.add("summary");
            terms.add("report");
        }
        addIfPresent(terms, normalized, "天气");
        if (normalized.contains("天气")) {
            terms.add("weather");
        }
        addIfPresent(terms, normalized, "github");
        addIfPresent(terms, normalized, "skill");
        addIfPresent(terms, normalized, "技能");
        if (normalized.contains("技能")) {
            terms.add("skill");
        }
        addIfPresent(terms, normalized, "tmux");
        return terms;
    }

    private void addIfPresent(Set<String> terms, String text, String term) {
        if (text.contains(term)) {
            terms.add(term);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record ScoredSkill(SkillInfo skill, int score) {
    }
}
