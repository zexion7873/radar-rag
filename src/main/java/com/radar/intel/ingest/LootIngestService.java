package com.radar.intel.ingest;

import com.radar.intel.notion.LootRow;
import com.radar.intel.notion.NotionClient;
import com.radar.intel.notion.NotionProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P5: pull the two Loot archive tables (Claude / Copilot) from Notion and embed each
 * row into pgvector. They stay two sources ({@code loot-claude} / {@code loot-copilot})
 * because upstream they ARE two archives — and that keeps them reachable through the
 * existing {@code source} search filter without new API surface.
 */
@Service
public class LootIngestService {

    public static final String SOURCE_CLAUDE = "loot-claude";
    public static final String SOURCE_COPILOT = "loot-copilot";

    private final NotionClient notion;
    private final NotionProperties props;
    private final VectorStore vectorStore;

    public LootIngestService(NotionClient notion, NotionProperties props, VectorStore vectorStore) {
        this.notion = notion;
        this.props = props;
        this.vectorStore = vectorStore;
    }

    /** @return embedded-document count per loot source. */
    public Map<String, Integer> sync() {
        Map<String, Integer> counts = new HashMap<>();
        counts.put(SOURCE_CLAUDE, syncTable(SOURCE_CLAUDE, props.lootClaudeDataSource()));
        counts.put(SOURCE_COPILOT, syncTable(SOURCE_COPILOT, props.lootCopilotDataSource()));
        return counts;
    }

    private int syncTable(String source, String dataSourceUuid) {
        List<Document> docs = new ArrayList<>();
        for (LootRow r : latestPerKey(notion.fetchLoot(dataSourceUuid))) {
            Document d = toDocument(r, source);
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
     * Loot is one row per candidate per WEEK upstream (see github-radar-ui's
     * latestLootPerRepo). Since {@link #toDocument}'s id key omits the week, those weekly
     * rows would collapse under pgvector's upsert to whichever the batch happened to write
     * last — arbitrary, because the Notion query is unsorted. Collapse deterministically to
     * each key's most recent week here instead, so the surviving row is defined and the
     * per-source count is the true distinct-document count.
     */
    static List<LootRow> latestPerKey(List<LootRow> rows) {
        Map<String, LootRow> byKey = new LinkedHashMap<>();
        List<LootRow> keyless = new ArrayList<>();
        for (LootRow r : rows) {
            String key = firstNonBlank(r.link(), r.repo());
            if (key == null) {
                keyless.add(r);  // no stable id key => gets a random id, never collides
                continue;
            }
            LootRow prev = byKey.get(key);
            if (prev == null || nullToEmpty(r.week()).compareTo(nullToEmpty(prev.week())) > 0) {
                byKey.put(key, r);
            }
        }
        List<LootRow> out = new ArrayList<>(byKey.values());
        out.addAll(keyless);
        return out;
    }

    /**
     * Maps one Loot row to its embedded Document (same contract as
     * {@link TrendingIngestService#toDocument}: public + static so the eval harness
     * exercises the exact production mapping; null when there is nothing to embed).
     *
     * <p>The id key is prefixed with the source: the same repo URL legitimately appears
     * in Trending AND a Loot table, and unprefixed name-based UUIDs would silently
     * upsert-collapse those into one row. Trending ids stay unprefixed — they predate
     * this and re-keying them would orphan every already-embedded row.
     */
    public static Document toDocument(LootRow r, String source) {
        String content = String.join("\n\n", r.repo(), r.asset(), r.intro(), r.why(), r.how()).strip();
        if (content.isBlank()) {
            return null;
        }
        Map<String, Object> md = new HashMap<>();
        md.put("source", source);
        putIfPresent(md, "repo", r.repo());
        putIfPresent(md, "week", r.week());
        putIfPresent(md, "category", r.type());
        putIfPresent(md, "url", r.link());
        putIfPresent(md, "status", r.status());

        Document.Builder b = Document.builder().text(content).metadata(md);
        String key = firstNonBlank(r.link(), r.repo());
        if (key != null) {
            b.id(UUID.nameUUIDFromBytes((source + "|" + key).getBytes(StandardCharsets.UTF_8)).toString());
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
