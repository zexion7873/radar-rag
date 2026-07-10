package com.radar.intel.ingest;

import com.radar.intel.notion.BlogRow;
import com.radar.intel.notion.NotionClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** P5: pull the Blog archive table from Notion and embed each post into pgvector. */
@Service
public class BlogIngestService {

    public static final String SOURCE = "blog";

    private final NotionClient notion;
    private final VectorStore vectorStore;

    public BlogIngestService(NotionClient notion, VectorStore vectorStore) {
        this.notion = notion;
        this.vectorStore = vectorStore;
    }

    /** @return the number of documents embedded. */
    public int sync() {
        List<Document> docs = new ArrayList<>();
        for (BlogRow r : notion.fetchBlog()) {
            Document d = toDocument(r);
            if (d != null) {
                docs.add(d);
            }
        }
        if (!docs.isEmpty()) {
            vectorStore.add(docs);
        }
        return docs.size();
    }

    /**
     * Maps one Blog row to its embedded Document (same contract as
     * {@link TrendingIngestService#toDocument}; null when there is nothing to embed).
     *
     * <p>Blog rows have no repo — the display identity is the title (surfaced under a
     * {@code title} metadata key, which /ask citations use when {@code repo} is absent)
     * and the time axis is the published date, mapped onto the shared {@code week} key
     * so the existing week filter works across sources. Id keys are source-prefixed
     * for the same collision reason as loot.
     */
    public static Document toDocument(BlogRow r) {
        String content = String.join("\n\n", r.title(), r.brief(), r.summary(), r.comment()).strip();
        if (content.isBlank()) {
            return null;
        }
        Map<String, Object> md = new HashMap<>();
        md.put("source", SOURCE);
        putIfPresent(md, "title", r.title());
        putIfPresent(md, "week", r.published());
        putIfPresent(md, "category", r.type());
        putIfPresent(md, "url", r.url());
        putIfPresent(md, "author", r.author());

        Document.Builder b = Document.builder().text(content).metadata(md);
        String key = firstNonBlank(r.url(), r.title());
        if (key != null) {
            b.id(UUID.nameUUIDFromBytes((SOURCE + "|" + key).getBytes(StandardCharsets.UTF_8)).toString());
        }
        return b.build();
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
