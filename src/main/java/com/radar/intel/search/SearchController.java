package com.radar.intel.search;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class SearchController {

    private final VectorStore vectorStore;

    public SearchController(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public record SearchQuery(String q, Integer topK) {
    }

    public record SearchHit(String text, Map<String, Object> metadata, Double score) {
    }

    /** Semantic search over the embedded radar archive. */
    @PostMapping("/search")
    public List<SearchHit> search(@RequestBody SearchQuery req) {
        int k = (req.topK() != null && req.topK() > 0) ? req.topK() : 10;
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query(req.q()).topK(k).build());
        return hits.stream()
                .map(d -> new SearchHit(d.getText(), d.getMetadata(), d.getScore()))
                .toList();
    }
}
