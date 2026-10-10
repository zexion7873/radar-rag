package com.radar.intel.search;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;

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

    private final VectorStore vectorStore;

    public RowSearch(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
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
