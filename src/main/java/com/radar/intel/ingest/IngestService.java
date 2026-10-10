package com.radar.intel.ingest;

import com.radar.intel.notion.BlogRow;
import com.radar.intel.notion.NotionClient;
import com.radar.intel.notion.TrendingRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Pulls each Notion table and embeds its rows into pgvector, one source at a time. */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final NotionClient notion;
    private final VectorStore vectorStore;
    private final TransactionTemplate tx;

    public IngestService(NotionClient notion, VectorStore vectorStore, PlatformTransactionManager txManager) {
        this.notion = notion;
        this.vectorStore = vectorStore;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Rows embedded per source, their total, and the sources that failed with a reason fit for a caller. */
    public record SyncResult(int ingested, Map<String, Integer> sources, Map<String, String> failed) {
    }

    /**
     * Replaces each source's rows with its table's current rows, so a row deleted in Notion does not
     * linger. Each source is its own transaction: a failure rolls back that source's delete and leaves
     * the other sources synced.
     */
    public SyncResult sync() {
        Map<String, Integer> sources = new LinkedHashMap<>();
        Map<String, String> failed = new LinkedHashMap<>();
        refresh("trending", () -> notion.fetchTrending().stream().map(IngestService::trending).toList(),
                sources, failed);
        refresh("blog", () -> notion.fetchBlog().stream().map(IngestService::blog).toList(), sources, failed);
        int total = sources.values().stream().mapToInt(Integer::intValue).sum();
        return new SyncResult(total, sources, failed);
    }

    private void refresh(String source, Supplier<List<Document>> fetch,
            Map<String, Integer> sources, Map<String, String> failed) {
        try {
            List<Document> docs = fetch.get().stream().filter(Objects::nonNull).toList();
            tx.executeWithoutResult(status -> {
                vectorStore.delete("source == '" + source + "'");
                if (!docs.isEmpty()) {
                    vectorStore.add(docs);
                }
            });
            sources.put(source, docs.size());
        } catch (RuntimeException e) {
            log.warn("sync of {} failed", source, e);
            failed.put(source, reason(e));
        }
    }

    /** Notion's status code at most: its error body, and any other cause's message, stays in the log. */
    static String reason(RuntimeException e) {
        return switch (e) {
            case RestClientResponseException r -> "upstream " + r.getStatusCode().value();
            case RestClientException _ -> "upstream unreachable";
            default -> "sync failed";
        };
    }

    /**
     * Maps one Trending row to its embedded Document, keyed by the Notion page id: the table holds one
     * row per repo per week, so a url-derived id let a repo's weeks overwrite each other.
     *
     * @return the Document, or null when the row has no embeddable text.
     */
    static Document trending(TrendingRow r) {
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

    /**
     * Maps one Blog row to its embedded Document. Its week is the publication date, or the archive date
     * when the post has none (the blog routine always sets Archived), cut to the day like trending's.
     *
     * @return the Document, or null when the row has no embeddable text.
     */
    static Document blog(BlogRow r) {
        String content = String.join("\n\n", r.title(), r.brief(), r.comment()).strip();
        if (content.isBlank()) {
            return null;
        }
        Map<String, Object> md = new HashMap<>();
        md.put("source", "blog");
        putIfPresent(md, "title", r.title());
        putIfPresent(md, "url", r.url());
        putIfPresent(md, "category", r.type());
        String date = r.published() != null ? r.published() : r.archived();
        if (date != null) {
            md.put("week", date.substring(0, Math.min(10, date.length())));
        }
        return Document.builder().id(r.pageId()).text(content).metadata(md).build();
    }

    private static void putIfPresent(Map<String, Object> md, String key, String val) {
        if (val != null && !val.isBlank()) {
            md.put(key, val);
        }
    }
}
