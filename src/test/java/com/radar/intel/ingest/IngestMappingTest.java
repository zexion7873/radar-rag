package com.radar.intel.ingest;

import com.radar.intel.notion.BlogRow;
import com.radar.intel.notion.LootRow;
import com.radar.intel.notion.TrendingRow;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for the P5 row→Document mappings — no Spring, no container. */
class IngestMappingTest {

    private static final String URL = "https://github.com/browser-use/browser-use";

    @Test
    void sameLinkAcrossSourcesYieldsDistinctDocuments() {
        Document trending = TrendingIngestService.toDocument(new TrendingRow(
                "browser-use/browser-use", "2026-06-27", 5900.0, "Python", "agents", URL, "d", "c"));
        Document lootClaude = LootIngestService.toDocument(new LootRow(
                "browser-use/browser-use", "i", "a", "mcp", "2026-06-27", URL, "w", "h", "new"),
                LootIngestService.SOURCE_CLAUDE);
        Document lootCopilot = LootIngestService.toDocument(new LootRow(
                "browser-use/browser-use", "i", "a", "mcp", "2026-06-27", URL, "w", "h", "new"),
                LootIngestService.SOURCE_COPILOT);
        Document blog = BlogIngestService.toDocument(new BlogRow(
                "t", URL, "s", "official", "a", "2026-06-27", "b", "s", "c"));

        // One URL, four sources — four documents. Unprefixed name-based UUIDs would
        // upsert-collapse them into one row in pgvector.
        assertThat(Set.of(trending.getId(), lootClaude.getId(), lootCopilot.getId(), blog.getId()))
                .hasSize(4);
    }

    @Test
    void lootCollapsesToLatestWeekPerKey() {
        LootRow earlier = new LootRow("acme/tool", "i", "a", "skill", "2026-06-13",
                "https://github.com/acme/tool", "w", "h", "adopted");
        LootRow later = new LootRow("acme/tool", "i", "a", "skill", "2026-06-27",
                "https://github.com/acme/tool", "w", "h", "new");
        LootRow other = new LootRow("acme/other", "i", "a", "mcp", "2026-06-20",
                "https://github.com/acme/other", "w", "h", "new");

        // Deliberately earlier-first so a naive last-wins would keep the wrong week.
        List<LootRow> kept = LootIngestService.latestPerKey(List.of(earlier, later, other));

        assertThat(kept).hasSize(2);
        assertThat(kept).filteredOn(r -> r.link().endsWith("/tool"))
                .singleElement()
                .satisfies(r -> assertThat(r.week()).isEqualTo("2026-06-27"));
    }

    @Test
    void blankRowsMapToNull() {
        assertThat(LootIngestService.toDocument(
                new LootRow("", "", "", null, null, null, "", "", null),
                LootIngestService.SOURCE_CLAUDE)).isNull();
        assertThat(BlogIngestService.toDocument(
                new BlogRow("", null, "", null, "", null, "", "", ""))).isNull();
    }

    @Test
    void lootMetadataUsesSharedFilterKeys() {
        Document d = LootIngestService.toDocument(new LootRow(
                "anthropics/skills", "intro", "asset", "skill", "2026-06-27",
                "https://github.com/anthropics/skills", "why", "how", "new"),
                LootIngestService.SOURCE_CLAUDE);
        assertThat(d.getMetadata())
                .containsEntry("source", "loot-claude")
                .containsEntry("category", "skill")     // loot Type rides the shared category key
                .containsEntry("week", "2026-06-27")
                .containsEntry("status", "new")
                .containsEntry("repo", "anthropics/skills");
    }

    @Test
    void blogIdentityIsTitleNotRepo() {
        Document d = BlogIngestService.toDocument(new BlogRow(
                "Some post", "https://example.dev/p", "Src", "official", "Auth",
                "2026-06-20", "brief", "summary", "comment"));
        assertThat(d.getMetadata())
                .containsEntry("source", "blog")
                .containsEntry("title", "Some post")
                .containsEntry("week", "2026-06-20")    // published rides the shared week key
                .doesNotContainKey("repo");
    }
}
