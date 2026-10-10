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
    // pooled ranking gave it two thirds of the slots and pushed out the repos questions ask about.
    private static final List<String> SOURCES = List.of("trending", "blog");

    private final VectorStore vectorStore;

    public RowSearch(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * The top k of each source, taken in turns, starting with the source whose best row scores higher;
     * a source that runs out leaves the rest to the other. filter null = no restriction beyond source.
     */
    public List<Document> balanced(String q, int k, Filter.Expression filter) {
        List<List<Document>> perSource = new ArrayList<>();
        for (String source : SOURCES) {
            Filter.Expression only = new FilterExpressionBuilder().eq("source", source).build();
            perSource.add(search(q, k, filter == null ? only
                    : new Filter.Expression(Filter.ExpressionType.AND, filter, only)));
        }
        perSource.sort(Comparator.comparing((List<Document> l) -> l.isEmpty() ? 0.0 : l.getFirst().getScore())
                .reversed());
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
        List<Document> candidates = vectorStore.similaritySearch(SearchRequest.builder()
                .query(q)
                .topK(k * CANDIDATES_PER_SLOT)
                .similarityThresholdAll()
                .filterExpression(filter)
                .build());
        Map<Object, Document> bestPerUrl = new LinkedHashMap<>();
        for (Document d : candidates) {
            bestPerUrl.putIfAbsent(Objects.requireNonNullElse(d.getMetadata().get("url"), d.getId()), d);
        }
        return bestPerUrl.values().stream().limit(k).toList();
    }
}
