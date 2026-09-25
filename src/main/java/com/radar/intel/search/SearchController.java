package com.radar.intel.search;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
public class SearchController {

    private final VectorStore vectorStore;

    public SearchController(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    // Spring AI 1.0.0's PgVector filter converter interpolates string filter values into the
    // jsonpath SQL literal WITHOUT escaping, so a quote in a value breaks out of the SQL string
    // (injection / 500). These filters are enum-like metadata that never legitimately contains
    // quotes or backslashes, so we reject those at the boundary.
    private static final Pattern UNSAFE_FILTER = Pattern.compile("[\"'\\\\]");

    /** P1: the string filters are exact matches on ingest metadata; minStars is a lower bound (stars_per_week >= minStars). */
    public record SearchQuery(String q, Integer topK,
                              String source, String category, String language, String week,
                              Double minStars) {
    }

    public record SearchHit(String id, String text, Map<String, Object> metadata, Double score) {
    }

    /** Semantic search over the embedded radar archive, optionally filtered by metadata. */
    @PostMapping("/search")
    public List<SearchHit> search(@RequestBody SearchQuery req) {
        validate(req);
        int k = (req.topK() != null && req.topK() > 0) ? req.topK() : 10;
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query(req.q()).topK(k)
                        .filterExpression(toFilter(req)).build());
        return hits.stream()
                .map(d -> new SearchHit(d.getId(), d.getText(), d.getMetadata(), d.getScore()))
                .toList();
    }

    private static void validate(SearchQuery req) {
        if (!hasText(req.q())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q is required");
        }
        rejectUnsafe("source", req.source());
        rejectUnsafe("category", req.category());
        rejectUnsafe("language", req.language());
        rejectUnsafe("week", req.week());
    }

    private static void rejectUnsafe(String field, String value) {
        if (value != null && UNSAFE_FILTER.matcher(value).find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must not contain quotes or backslashes");
        }
    }

    private static Filter.Expression toFilter(SearchQuery req) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        List<FilterExpressionBuilder.Op> ops = new ArrayList<>();
        if (hasText(req.source())) {
            ops.add(b.eq("source", req.source()));
        }
        if (hasText(req.category())) {
            ops.add(b.eq("category", req.category()));
        }
        if (hasText(req.language())) {
            ops.add(b.eq("language", req.language()));
        }
        if (hasText(req.week())) {
            ops.add(b.eq("week", req.week()));
        }
        if (req.minStars() != null) {
            ops.add(b.gte("stars_per_week", req.minStars()));
        }
        // null = unfiltered; SearchRequest accepts a @Nullable expression.
        return ops.isEmpty() ? null : ops.stream().reduce(b::and).orElseThrow().build();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
