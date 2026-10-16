package com.radar.intel.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * BM25 over one source's rows, for the words a question names that the embedding blurs: "晶片" or "本機" in
 * a broad question decides which post answers it, and MiniLM's mean over 128 tokens of a long post ranks
 * those posts outside the top 30. English words are lowercased with a plural cut; Chinese, which has no
 * spaces, is cut into overlapping character pairs once the question's filler ("有沒有", "相關", "文章") is
 * taken out. Immutable: KeywordIndex builds a new one when a source's rows change.
 */
final class Keywords {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private static final Pattern LATIN = Pattern.compile("[a-z0-9]+(?:[.\\-][a-z0-9]+)*");
    private static final Pattern CJK = Pattern.compile("[\\x{3400}-\\x{9FFF}\\x{F900}-\\x{FAFF}]+");

    // Words that ask for something rather than name it; every row would match "article" or "related".
    private static final Set<String> LATIN_STOP = Set.of(
            "a", "an", "and", "any", "anyone", "are", "article", "as", "at", "about", "be", "by", "can", "do",
            "does", "for", "from", "has", "how", "i", "in", "information", "is", "it", "me", "my", "now", "of",
            "on", "or", "post", "related", "share", "shared", "some", "that", "the", "their", "them", "there",
            "they", "this", "to", "we", "what", "where", "which", "with", "you", "your");

    // Taken out before pairing, so "有沒有AI關於醫療領域的相關文章" pairs only 醫療領域; longest first.
    private static final Pattern CJK_STOP = Pattern.compile(List.of(
                    "有沒有", "有關於", "關於", "有關", "相關", "文章", "資訊", "哪些", "什麼", "現在", "可以",
                    "的", "嗎", "呢", "了", "是", "有", "在", "和", "與", "及", "或", "對", "做", "用", "能", "到", "上")
            .stream().sorted(Comparator.comparingInt(String::length).reversed())
            .collect(Collectors.joining("|")));

    private final Map<String, Map<String, Integer>> termsById;
    private final Map<String, Integer> lengthById;
    private final Map<String, Integer> docFreq;
    private final double avgLength;

    /** texts: row id to its embedded text. */
    Keywords(Map<String, String> texts) {
        termsById = new HashMap<>();
        lengthById = new HashMap<>();
        docFreq = new HashMap<>();
        long total = 0;
        for (Map.Entry<String, String> e : texts.entrySet()) {
            Map<String, Integer> tf = new HashMap<>();
            List<String> terms = terms(e.getValue());
            terms.forEach(t -> tf.merge(t, 1, Integer::sum));
            termsById.put(e.getKey(), tf);
            lengthById.put(e.getKey(), terms.size());
            tf.keySet().forEach(t -> docFreq.merge(t, 1, Integer::sum));
            total += terms.size();
        }
        avgLength = texts.isEmpty() ? 1 : Math.max(1, (double) total / texts.size());
    }

    /** Each row's BM25 score for q, rows that share no term with it left out. */
    Map<String, Double> scores(String q) {
        Set<String> query = new LinkedHashSet<>(terms(q));
        int n = termsById.size();
        Map<String, Double> idf = new HashMap<>();
        for (String t : query) {
            int df = docFreq.getOrDefault(t, 0);
            if (df > 0) {
                idf.put(t, Math.log(1 + (n - df + 0.5) / (df + 0.5)));
            }
        }
        Map<String, Double> out = new HashMap<>();
        if (idf.isEmpty()) {
            return out;
        }
        termsById.forEach((id, tf) -> {
            double norm = K1 * (1 - B + B * lengthById.get(id) / avgLength);
            double s = 0;
            for (Map.Entry<String, Double> w : idf.entrySet()) {
                Integer f = tf.get(w.getKey());
                if (f != null) {
                    s += w.getValue() * f * (K1 + 1) / (f + norm);
                }
            }
            if (s > 0) {
                out.put(id, s);
            }
        });
        return out;
    }

    static List<String> terms(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        Matcher latin = LATIN.matcher(lower);
        while (latin.find()) {
            String w = stem(latin.group());
            if (w.length() > 1 && !LATIN_STOP.contains(w)) {
                out.add(w);
            }
        }
        Matcher cjk = CJK.matcher(CJK_STOP.matcher(lower).replaceAll(" "));
        while (cjk.find()) {
            String run = cjk.group();
            for (int i = 0; i + 2 <= run.length(); i++) {
                out.add(run.substring(i, i + 2));
            }
        }
        return out;
    }

    // "chips" and "chip", "companies" and "company"; "class", "status" and "analysis" stay whole.
    private static String stem(String w) {
        if (w.length() > 4 && w.endsWith("ies")) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }
}
