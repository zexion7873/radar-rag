package com.radar.intel.ingest;

import com.radar.intel.notion.NotionClient;
import com.radar.intel.notion.TrendingRow;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** P0: pull the Trending table from Notion and embed each row into pgvector. */
@Service
public class TrendingIngestService {

    private final NotionClient notion;
    private final VectorStore vectorStore;

    public TrendingIngestService(NotionClient notion, VectorStore vectorStore) {
        this.notion = notion;
        this.vectorStore = vectorStore;
    }

    /**
     * Replaces every trending row with the table's current rows, so a row deleted in Notion does not
     * linger. One transaction: a failed embed rolls the delete back instead of emptying the source.
     *
     * @return the number of documents embedded.
     */
    @Transactional
    public int sync() {
        List<Document> docs = new ArrayList<>();
        for (TrendingRow r : notion.fetchTrending()) {
            Document d = toDocument(r);
            if (d != null) {
                docs.add(d);
            }
        }
        vectorStore.delete("source == 'trending'");
        if (!docs.isEmpty()) {
            vectorStore.add(docs);
        }
        return docs.size();
    }

    /**
     * Maps one Trending row to its embedded Document, keyed by the Notion page id: the table holds one
     * row per repo per week, so a url-derived id let a repo's weeks overwrite each other.
     *
     * @return the Document, or null when the row has no embeddable text.
     */
    static Document toDocument(TrendingRow r) {
        String content = String.join("\n\n", r.repo(), r.description(), r.comment()).strip();
        if (content.isBlank()) {
            return null;
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
        // PgVectorStore's id column is a uuid, and Notion page ids are UUIDs.
        return Document.builder().id(r.pageId()).text(content).metadata(md).build();
    }

    private static void putIfPresent(Map<String, Object> md, String key, String val) {
        if (val != null && !val.isBlank()) {
            md.put(key, val);
        }
    }
}
