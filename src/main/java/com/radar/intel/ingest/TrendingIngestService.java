package com.radar.intel.ingest;

import com.radar.intel.notion.NotionClient;
import com.radar.intel.notion.TrendingRow;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** P0: pull the Trending table from Notion and embed each row into pgvector. */
@Service
public class TrendingIngestService {

    private final NotionClient notion;
    private final VectorStore vectorStore;

    public TrendingIngestService(NotionClient notion, VectorStore vectorStore) {
        this.notion = notion;
        this.vectorStore = vectorStore;
    }

    /** @return the number of documents embedded. */
    public int sync() {
        List<Document> docs = new ArrayList<>();
        for (TrendingRow r : notion.fetchTrending()) {
            String content = String.join("\n\n", r.repo(), r.description(), r.comment()).strip();
            if (content.isBlank()) {
                continue;
            }
            Map<String, Object> md = new HashMap<>();
            md.put("source", "trending");
            putIfPresent(md, "repo", r.repo());
            putIfPresent(md, "week", r.week());
            putIfPresent(md, "category", r.category());
            putIfPresent(md, "language", r.language());
            putIfPresent(md, "url", r.link());
            if (r.starsPerWeek() != null) {
                md.put("stars_per_week", r.starsPerWeek());
            }

            // PgVectorStore stores ids in a uuid column and calls UUID.fromString(id),
            // so the id MUST be a valid UUID (a raw URL throws "Invalid UUID string").
            // Derive a STABLE name-based UUID from the URL (else repo) => re-sync UPSERTS
            // the same row instead of duplicating.
            Document.Builder b = Document.builder().text(content).metadata(md);
            String key = firstNonBlank(r.link(), r.repo());
            if (key != null) {
                b.id(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString());
            }
            docs.add(b.build());
        }
        if (!docs.isEmpty()) {
            vectorStore.add(docs);
        }
        return docs.size();
    }

    private static void putIfPresent(Map<String, Object> md, String key, String val) {
        if (val != null && !val.isBlank()) {
            md.put(key, val);
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return (b != null && !b.isBlank()) ? b : null;
    }
}
