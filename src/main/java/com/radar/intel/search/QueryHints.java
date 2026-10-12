package com.radar.intel.search;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a question asks for beyond its topic: a trending category it names as a category, and recency.
 * A category word counts only beside a word that makes it one ("apps 類", "agent類型", "infra
 * category"): golden_v1 says "agent" or "tools" in 22 questions that ask about a topic, not a category.
 */
record QueryHints(String category, boolean recent) {

    // Trending's Category values (Notion select options), each with the words a question uses for it.
    // "other" is left out: "其他類似" would read as one.
    private static final Map<String, String> CATEGORY_WORDS = new LinkedHashMap<>();

    static {
        CATEGORY_WORDS.put("agents", "agents?|代理|智能體");
        CATEGORY_WORDS.put("tooling", "tools?|tooling|工具");
        CATEGORY_WORDS.put("apps", "apps?|applications?|應用(?:程式)?");
        CATEGORY_WORDS.put("infra", "infra(?:structure)?|基礎設施|基礎建設");
        CATEGORY_WORDS.put("models", "models?|模型");
    }

    private static final Map<String, Pattern> CATEGORY_PATTERNS = new LinkedHashMap<>();

    static {
        CATEGORY_WORDS.forEach((category, words) -> CATEGORY_PATTERNS.put(category, Pattern.compile(
                // "apps 類", "agent類型", but not "agent類似"
                "(?<![A-Za-z])(?:" + words + ")\\s*類(?!似)"
                        // "infra category", "category: apps", "分類是 apps"
                        + "|\\b(?:" + words + ")\\s+categor(?:y|ies)\\b"
                        + "|(?:\\bcategor(?:y|ies)|分類)\\s*(?:是|為|of|[:：=])?\\s*(?:" + words + ")(?![A-Za-z])",
                Pattern.CASE_INSENSITIVE)));
    }

    private static final Pattern RECENT = Pattern.compile(
            "最近|近期|最新|\\brecent(?:ly)?\\b|\\blatest\\b|\\blately\\b", Pattern.CASE_INSENSITIVE);

    static QueryHints of(String q) {
        String category = CATEGORY_PATTERNS.entrySet().stream()
                .filter(e -> e.getValue().matcher(q).find())
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
        return new QueryHints(category, RECENT.matcher(q).find());
    }
}
