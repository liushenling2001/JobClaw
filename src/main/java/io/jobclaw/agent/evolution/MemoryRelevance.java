package io.jobclaw.agent.evolution;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Conservative lexical relevance, not an intent router or semantic classifier. */
public final class MemoryRelevance {
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "this", "that", "please", "can", "could", "would",
            "should", "you", "your", "from", "have", "has", "are", "was", "what", "how",
            "when", "where", "which", "will", "then", "just", "into", "about", "not");

    private MemoryRelevance() { }

    public static int score(String query, String content) {
        if (query == null || query.isBlank() || content == null || content.isBlank()) {
            return 0;
        }
        Terms left = terms(query);
        Terms right = terms(content);
        long words = left.words.stream().filter(right.words::contains).count();
        long hanPairs = left.hanPairs.stream().filter(right.hanPairs::contains).count();
        // A single generic two/three-character noun is not enough to admit Chinese task memory.
        return (int) words + (hanPairs >= 3 ? (int) hanPairs : 0);
    }

    private static Terms terms(String value) {
        Set<String> words = new HashSet<>();
        Set<String> pairs = new HashSet<>();
        StringBuilder word = new StringBuilder();
        int previousHan = -1;
        for (int point : value.toLowerCase(Locale.ROOT).codePoints().toArray()) {
            if (Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN) {
                addWord(words, word);
                if (previousHan != -1) {
                    pairs.add(new String(Character.toChars(previousHan)) + new String(Character.toChars(point)));
                }
                previousHan = point;
            } else {
                previousHan = -1;
                if (Character.isLetterOrDigit(point) || point == '_') {
                    word.appendCodePoint(point);
                } else {
                    addWord(words, word);
                }
            }
        }
        addWord(words, word);
        return new Terms(words, pairs);
    }

    private static void addWord(Set<String> words, StringBuilder word) {
        if (word.length() >= 2 && !STOP_WORDS.contains(word.toString())) {
            words.add(word.toString());
        }
        word.setLength(0);
    }

    private record Terms(Set<String> words, Set<String> hanPairs) { }
}
