package io.jobclaw.runtime.tool.discovery;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ToolDiscoveryCatalog {

    private static final double BM25_K1 = 1.2d;
    private static final double BM25_B = 0.75d;
    private static final Map<String, String> CAPABILITY_ALIASES = Map.ofEntries(
            Map.entry("read_pdf", "PDF document 论文 文档"),
            Map.entry("read_word", "Word DOC DOCX document 文档"),
            Map.entry("read_excel", "Excel XLS XLSX spreadsheet 表格 电子表格"),
            Map.entry("web_search", "web internet current latest search 网页 联网 最新 搜索"),
            Map.entry("web_fetch", "web URL page fetch 网页 链接 抓取"),
            Map.entry("message", "message notify notification channel 消息 通知 渠道"),
            Map.entry("memory", "memory remember recall preference 记忆 记住 偏好"),
            Map.entry("spawn", "child subagent delegate 子智能体 委派"),
            Map.entry("collaborate", "multi agent parallel team collaboration 多智能体 并行 协作"),
            Map.entry("agent_catalog", "saved persistent agent catalog 智能体 目录"),
            Map.entry("board_write", "shared board write note 协作 看板 写入"),
            Map.entry("board_read", "shared board read 协作 看板 读取"),
            Map.entry("cron", "schedule reminder recurring 定时 提醒 周期"),
            Map.entry("mcp", "model context protocol external server MCP 外部服务"),
            Map.entry("query_token_usage", "token usage cost statistics 用量 费用 统计")
    );

    private final List<Entry> entries;
    private final Map<String, Entry> entriesByName;
    private final double averageDocumentLength;

    public ToolDiscoveryCatalog(Collection<ToolCallback> callbacks) {
        LinkedHashMap<String, Entry> indexed = new LinkedHashMap<>();
        if (callbacks != null) {
            for (ToolCallback callback : callbacks) {
                if (callback == null || callback.getToolDefinition() == null) {
                    continue;
                }
                ToolDefinition definition = callback.getToolDefinition();
                String name = normalizeName(definition.name());
                if (name.isBlank() || indexed.containsKey(name)) {
                    continue;
                }
                String document = String.join(" ",
                        name.replace('_', ' '),
                        nullToEmpty(definition.description()),
                        nullToEmpty(definition.inputSchema()),
                        CAPABILITY_ALIASES.getOrDefault(name, "")
                );
                List<String> terms = tokenize(document);
                indexed.put(name, new Entry(callback, definition, termFrequency(terms), terms.size()));
            }
        }
        this.entries = List.copyOf(indexed.values());
        this.entriesByName = Map.copyOf(indexed);
        this.averageDocumentLength = this.entries.stream()
                .mapToInt(Entry::documentLength)
                .average()
                .orElse(1.0d);
    }

    public List<Match> search(String query, int limit) {
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty() || entries.isEmpty()) {
            return List.of();
        }
        int effectiveLimit = Math.max(1, Math.min(limit, 8));
        Map<String, Integer> documentFrequency = documentFrequency(queryTerms);
        String normalizedQuery = normalizeName(query);
        List<Match> matches = new ArrayList<>();
        for (Entry entry : entries) {
            double score = bm25(entry, queryTerms, documentFrequency);
            String name = normalizeName(entry.definition().name());
            if (name.equals(normalizedQuery)) {
                score += 20.0d;
            } else if (!normalizedQuery.isBlank() && name.contains(normalizedQuery)) {
                score += 6.0d;
            }
            if (score > 0.0d) {
                matches.add(new Match(entry.callback(), entry.definition(), score));
            }
        }
        return matches.stream()
                .sorted(Comparator.comparingDouble(Match::score).reversed()
                        .thenComparing(match -> match.definition().name()))
                .limit(effectiveLimit)
                .toList();
    }

    public Optional<ToolCallback> find(String name) {
        Entry entry = entriesByName.get(normalizeName(name));
        return entry != null ? Optional.of(entry.callback()) : Optional.empty();
    }

    public int size() {
        return entries.size();
    }

    private double bm25(Entry entry,
                        List<String> queryTerms,
                        Map<String, Integer> documentFrequency) {
        double score = 0.0d;
        for (String term : queryTerms.stream().distinct().toList()) {
            int frequency = entry.termFrequency().getOrDefault(term, 0);
            if (frequency == 0) {
                continue;
            }
            int documentsWithTerm = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log(1.0d
                    + (entries.size() - documentsWithTerm + 0.5d) / (documentsWithTerm + 0.5d));
            double lengthNormalization = 1.0d - BM25_B
                    + BM25_B * entry.documentLength() / Math.max(1.0d, averageDocumentLength);
            score += idf * (frequency * (BM25_K1 + 1.0d))
                    / (frequency + BM25_K1 * lengthNormalization);
        }
        return score;
    }

    private Map<String, Integer> documentFrequency(List<String> queryTerms) {
        Map<String, Integer> frequency = new HashMap<>();
        for (String term : queryTerms.stream().distinct().toList()) {
            int count = 0;
            for (Entry entry : entries) {
                if (entry.termFrequency().containsKey(term)) {
                    count++;
                }
            }
            frequency.put(term, count);
        }
        return frequency;
    }

    private static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT).replace('_', ' ');
        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        StringBuilder han = new StringBuilder();
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isHan(codePoint)) {
                flushWord(word, tokens);
                han.appendCodePoint(codePoint);
            } else {
                flushHan(han, tokens);
                if (Character.isLetterOrDigit(codePoint)) {
                    word.appendCodePoint(codePoint);
                } else {
                    flushWord(word, tokens);
                }
            }
        }
        flushWord(word, tokens);
        flushHan(han, tokens);
        return tokens;
    }

    private static void flushWord(StringBuilder word, List<String> tokens) {
        if (!word.isEmpty()) {
            tokens.add(word.toString());
            word.setLength(0);
        }
    }

    private static void flushHan(StringBuilder han, List<String> tokens) {
        if (han.isEmpty()) {
            return;
        }
        int[] codePoints = han.codePoints().toArray();
        for (int codePoint : codePoints) {
            tokens.add(new String(Character.toChars(codePoint)));
        }
        for (int i = 0; i + 1 < codePoints.length; i++) {
            tokens.add(new String(Character.toChars(codePoints[i]))
                    + new String(Character.toChars(codePoints[i + 1])));
        }
        han.setLength(0);
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static Map<String, Integer> termFrequency(List<String> terms) {
        Map<String, Integer> frequency = new HashMap<>();
        for (String term : terms) {
            frequency.merge(term, 1, Integer::sum);
        }
        return frequency;
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    public record Match(ToolCallback callback, ToolDefinition definition, double score) {
    }

    private record Entry(
            ToolCallback callback,
            ToolDefinition definition,
            Map<String, Integer> termFrequency,
            int documentLength
    ) {
    }
}
