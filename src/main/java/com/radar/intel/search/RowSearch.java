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

    private final VectorStore vectorStore;
    private final RecentWeeks recentWeeks;

    public RowSearch(VectorStore vectorStore, RecentWeeks recentWeeks) {
        this.vectorStore = vectorStore;
        this.recentWeeks = recentWeeks;
    }

    /**
     * The top k of each source, taken in turns, starting with the source whose most similar row scores higher;
     * a source that runs out leaves the rest to the other. filter null = no restriction beyond source.
     * A category the question names becomes a filter unless the caller gave one (categoryGiven); blog
     * rows carry other categories, so trending then fills every slot. A question asking for recent rows
     * ranks each source's last 28 days higher.
     */
    public List<Document> balanced(String q, int k, Filter.Expression filter, boolean categoryGiven) {
        QueryHints hints = QueryHints.of(q);
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression narrowed = categoryGiven || hints.category() == null ? filter
                : and(filter, b.eq("category", hints.category()).build());
        List<List<Document>> perSource = new ArrayList<>();
        for (String source : SOURCES) {
            Filter.Expression only = and(narrowed, b.eq("source", source).build());
            perSource.add(hints.recent() ? search(q, k, only, recentWeeks.of(source)) : search(q, k, only));
        }
        // By the best similarity, not the first row: under a recency boost the first row is the best recent
        // one, and recency would then decide which source leads.
        perSource.sort(Comparator.comparingDouble((List<Document> l) -> l.stream()
                .mapToDouble(Document::getScore).max().orElse(0.0)).reversed());
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
     * candidates, then ranks by similarity plus RECENCY_BONUS on recent rows. Each url keeps its best row
     * by that rank, so a repo that charted recently comes back at its recent week.
     */
    private List<Document> search(String q, int k, Filter.Expression filter, List<String> weeks) {
        if (weeks.isEmpty()) {
            return search(q, k, filter);
        }
        List<Document> pool = new ArrayList<>(candidates(q, k, filter));
        pool.addAll(candidates(q, k, and(filter,
                new FilterExpressionBuilder().in("week", new ArrayList<Object>(weeks)).build())));
        pool.sort(Comparator.comparingDouble((Document d) -> d.getScore()
                + (weeks.contains(String.valueOf(d.getMetadata().get("week"))) ? RECENCY_BONUS : 0)).reversed());
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
