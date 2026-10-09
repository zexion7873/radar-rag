package com.radar.intel.ingest;

import com.radar.intel.notion.TrendingRow;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;

class TrendingIngestServiceTest {

    // Panniantong/Agent-Reach's pages for two weeks in the fixture, which a url-derived id collapsed.
    private static final String JUNE_PAGE = "3832e058-e02d-817c-b72f-d0d8ec070518";
    private static final String SEPT_PAGE = "3e22e058-e02d-8133-a886-f0131e48f04f";

    @Test
    void eachWeekOfOneRepoKeepsItsOwnDocumentId() {
        Document june = TrendingIngestService.toDocument(row(JUNE_PAGE, "2026-06-18", "desc"));
        Document sept = TrendingIngestService.toDocument(row(SEPT_PAGE, "2026-09-21", "desc"));

        assertThat(june.getId()).isEqualTo(JUNE_PAGE);
        assertThat(sept.getId()).isEqualTo(SEPT_PAGE);
        assertThat(sept.getMetadata())
                .containsEntry("source", "trending")
                .containsEntry("url", "https://github.com/a/one")
                .containsEntry("week", "2026-09-21");
    }

    @Test
    void aRowWithNoTextIsNotEmbedded() {
        TrendingRow blank = new TrendingRow(JUNE_PAGE, "", "2026-06-18", null, null, null,
                "https://github.com/a/one", "", "");

        assertThat(TrendingIngestService.toDocument(blank)).isNull();
    }

    private static TrendingRow row(String pageId, String week, String description) {
        return new TrendingRow(pageId, "a/one", week, 100.0, "Python", "agents",
                "https://github.com/a/one", description, "comment");
    }
}
