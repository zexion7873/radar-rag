package com.radar.intel.search;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Similarity search that returns each url once, at its best-scoring row: the Trending Archive holds a
 * row per repo per week, and a repo's weeks are near-identical text. /search and /ask both retrieve
 * through here, so the harness, which queries /search, measures what /ask grounds on.
 */
@Component
public class RowSearch {

    // No repo has charted for more than 5 weeks, so k * 5 candidates hold k distinct urls; a longer run
    // can leave fewer than k.
    private static final int CANDIDATES_PER_SLOT = 5;

    // The `source` values IngestService writes. Blog has 3x trending's rows and broader text, so one
    // pooled ranking gives it two thirds of the slots and pushes out the repos questions ask about.
    private static final List<String> SOURCES = List.of("trending", "blog");

    // The longest question /search and /ask take; github-radar-ui's page lets a visitor type 500 characters.
    public static final int MAX_QUESTION_LENGTH = 1000;

    // Added to a recent row's similarity when the question asks for recent ones: enough to lift a recent
    // row over most older ones, not enough to lift an off-topic one over the question's topic.
    static final double RECENCY_BONUS = 0.1;

    // Added to a blog row's similarity in proportion to its keyword (BM25) score over the question's best:
    // a post naming the question's rare words climbs past posts that only share its broad AI vocabulary.
    // Trending rows are a repo name and two sentences, where the embedding already finds the topic.
    static final double KEYWORD_BONUS = 0.2;

    private static final String KEYWORD_SOURCE = "blog";

    private static final String REPO_SOURCE = "trending";

    private final VectorStore vectorStore;
    private final RecentWeeks recentWeeks;
    private final KeywordIndex keywordIndex;
    private final RepoNames repoNames;

    public RowSearch(VectorStore vectorStore, RecentWeeks recentWeeks, KeywordIndex keywordIndex,
                     RepoNames repoNames) {
        this.vectorStore = vectorStore;
        this.recentWeeks = recentWeeks;
        this.keywordIndex = keywordIndex;
        this.repoNames = repoNames;
    }

    /**
     * The top k of each source, taken in turns, starting with trending when the question asks for or names a
     * repo and otherwise with the source whose most similar row scores higher; a source that runs out leaves
     * the rest to the other. A repo the question names as owner/name leads trending's rows.
     * filter null = no restriction beyond source.
     * A category the question names becomes a filter unless the caller gave one (categoryGiven); blog
     * rows carry other categories, so trending then fills every slot. A question asking for recent rows
     * ranks each source's last 28 days higher. Blog rows that share the question's rarer words rank higher.
     */
    public List<Document> balanced(String q, int k, Filter.Expression filter, boolean categoryGiven) {
        QueryHints hints = QueryHints.of(q);
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression narrowed = categoryGiven || hints.category() == null ? filter
                : and(filter, b.eq("category", hints.category()).build());
        List<String> named = repoNames.in(q);
        Map<String, List<Document>> bySource = new LinkedHashMap<>();
        for (String source : SOURCES) {
            Filter.Expression only = and(narrowed, b.eq("source", source).build());
            List<String> weeks = hints.recent() ? recentWeeks.of(source) : List.of();
            KeywordIndex.Match keywords = source.equals(KEYWORD_SOURCE)
                    ? keywordIndex.match(source, q, k * CANDIDATES_PER_SLOT) : KeywordIndex.Match.NONE;
            List<Document> found = search(q, k, only, weeks, keywords);
            if (source.equals(REPO_SOURCE) && !named.isEmpty()) {
                List<Document> pinned = new ArrayList<>(search(q, k,
                        and(only, b.in("repo", new ArrayList<Object>(named)).build()), weeks, KeywordIndex.Match.NONE));
                pinned.addAll(found);
                found = bestPerUrl(pinned, k);
            }
            bySource.put(source, found);
        }
        // A question asking for or naming a repo gets trending first: led by blog, it would hold 2 of 5 slots.
        // Otherwise by the best similarity, not the first row: under a recency boost the first row is the best
        // recent one, and recency would then decide which source leads.
        boolean repos = !named.isEmpty() || QueryHints.asksForRepos(q);
        List<List<Document>> perSource = bySource.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, List<Document>> e) ->
                                !(repos && e.getKey().equals(REPO_SOURCE)))
                        .thenComparing(Comparator.comparingDouble((Map.Entry<String, List<Document>> e) ->
                                e.getValue().stream().mapToDouble(Document::getScore).max().orElse(0.0)).reversed()))
                .map(Map.Entry::getValue)
                .toList();
        List<Document> out = new ArrayList<>();
        for (int i = 0; out.size() < k; i++) {
            boolean any = false;
            for (List<Document> l : perSource) {
                if (i < l.size() && out.size() < k) {
                    out.add(l.get(i));
                    any = true;
                }
            }
            if (!any) {
                break;
            }
        }
        return out;
    }

    /** filter null = every row. */
    public List<Document> search(String q, int k, Filter.Expression filter) {
        return bestPerUrl(candidates(q, k, filter), k);
    }

    /**
     * Also searches the recent weeks alone, so recent rows compete even when older ones fill the first
     * candidates, and the keyword match's best rows, so a post the embedding ranks far down still competes.
     * Then ranks by similarity plus RECENCY_BONUS on recent rows plus KEYWORD_BONUS scaled by the row's
     * keyword score. Each url keeps its best row by that rank, so a repo that charted recently comes back at
     * its recent week. Each row's score stays its plain similarity.
     */
    private List<Document> search(String q, int k, Filter.Expression filter, List<String> weeks,
                                  KeywordIndex.Match keywords) {
        if (weeks.isEmpty() && keywords.score().isEmpty()) {
            return search(q, k, filter);
        }
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        List<Document> pool = new ArrayList<>(candidates(q, k, filter));
        if (!weeks.isEmpty()) {
            pool.addAll(candidates(q, k, and(filter, b.in("week", new ArrayList<Object>(weeks)).build())));
        }
        if (!keywords.urls().isEmpty()) {
            pool.addAll(candidates(q, k, and(filter, b.in("url", keywords.urls()).build())));
        }
        pool.sort(Comparator.comparingDouble((Document d) -> d.getScore()
                + (weeks.contains(String.valueOf(d.getMetadata().get("week"))) ? RECENCY_BONUS : 0)
                + (keywords.max() > 0 ? KEYWORD_BONUS * keywords.score().getOrDefault(d.getId(), 0.0) / keywords.max() : 0))
                .reversed());
        return bestPerUrl(pool, k);
    }

    private List<Document> candidates(String q, int k, Filter.Expression filter) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(q)
                .topK(k * CANDIDATES_PER_SLOT)
                .similarityThresholdAll()
                .filterExpression(filter)
                .build());
    }

    private static List<Document> bestPerUrl(List<Document> ranked, int k) {
        Map<Object, Document> best = new LinkedHashMap<>();
        for (Document d : ranked) {
            best.putIfAbsent(Objects.requireNonNullElse(d.getMetadata().get("url"), d.getId()), d);
        }
        return best.values().stream().limit(k).toList();
    }

    private static Filter.Expression and(Filter.Expression left, Filter.Expression right) {
        return left == null ? right : new Filter.Expression(Filter.ExpressionType.AND, left, right);
    }
}
