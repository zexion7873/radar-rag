package com.radar.intel.ingest;

import com.radar.intel.notion.BlogRow;
import com.radar.intel.notion.TrendingRow;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestServiceTest {

    // Panniantong/Agent-Reach's pages for two weeks in the fixture, which a url-derived id collapsed.
    private static final String JUNE_PAGE = "3832e058-e02d-817c-b72f-d0d8ec070518";
    private static final String SEPT_PAGE = "3e22e058-e02d-8133-a886-f0131e48f04f";
    private static final String BLOG_PAGE = "1a2b3c4d-0000-4000-8000-000000000001";

    @Test
    void eachWeekOfOneRepoKeepsItsOwnDocumentId() {
        Document june = IngestService.trending(row(JUNE_PAGE, "2026-06-18", "desc"));
        Document sept = IngestService.trending(row(SEPT_PAGE, "2026-09-21", "desc"));

        assertThat(june.getId()).isEqualTo(JUNE_PAGE);
        assertThat(sept.getId()).isEqualTo(SEPT_PAGE);
        assertThat(sept.getMetadata())
                .containsEntry("source", "trending")
                .containsEntry("url", "https://github.com/a/one")
                .containsEntry("week", "2026-09-21");
    }

    @Test
    void everyWeekOfARepoCarriesItsWholeChartRun() {
        TrendingRow other = new TrendingRow("1a2b3c4d-0000-4000-8000-0000000000aa", "b/two", "2026-07-06", 5.0,
                null, null, "https://github.com/b/two", "desc", "comment");
        List<Document> docs = IngestService.trending(List.of(
                row(SEPT_PAGE, "2026-09-21", "desc"), row(JUNE_PAGE, "2026-06-18", "desc"), other));

        assertThat(docs.get(0).getMetadata())
                .containsEntry("weeks_on_chart", 2)
                .containsEntry("first_week", "2026-06-18")
                .containsEntry("last_week", "2026-09-21");
        assertThat(docs.get(1).getMetadata()).containsEntry("weeks_on_chart", 2);
        assertThat(docs.get(2).getMetadata())
                .containsEntry("weeks_on_chart", 1)
                .containsEntry("first_week", "2026-07-06")
                .containsEntry("last_week", "2026-07-06");
    }

    @Test
    void aRowWithNoTextIsNotEmbedded() {
        TrendingRow blank = new TrendingRow(JUNE_PAGE, "", "2026-06-18", null, null, null,
                "https://github.com/a/one", "", "");

        assertThat(IngestService.trending(blank)).isNull();
    }

    @Test
    void aBlogRowEmbedsTitleBriefAndCommentUnderItsPublicationDay() {
        Document d = IngestService.blog(new BlogRow(BLOG_PAGE, "A post", "https://x.test/p", "official",
                "2026-09-30T08:00:00.000+08:00", "2026-10-01", "the brief", "the comment"));

        assertThat(d.getId()).isEqualTo(BLOG_PAGE);
        assertThat(d.getText()).isEqualTo("A post\n\nthe brief\n\nthe comment");
        assertThat(d.getMetadata())
                .containsEntry("source", "blog")
                .containsEntry("title", "A post")
                .containsEntry("url", "https://x.test/p")
                .containsEntry("category", "official")
                .containsEntry("week", "2026-09-30")
                .doesNotContainKey("repo");
    }

    @Test
    void aBlogRowWithoutPublicationFallsBackToItsArchiveDay() {
        Document d = IngestService.blog(new BlogRow(BLOG_PAGE, "A post", "https://x.test/p", null,
                null, "2026-10-02T03:00:00.000+00:00", "", ""));

        assertThat(d.getMetadata()).containsEntry("week", "2026-10-02");
    }

    @Test
    void aBlankBlogRowIsNotEmbedded() {
        assertThat(IngestService.blog(new BlogRow(BLOG_PAGE, " ", "https://x.test/p", null,
                "2026-09-30", null, "", ""))).isNull();
    }

    @Test
    void aFailureReasonCarriesAtMostNotionsStatusCode() {
        HttpClientErrorException notFound = HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                null, "notion-body-must-not-leak".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        assertThat(IngestService.reason(notFound)).isEqualTo("upstream 404");
        assertThat(IngestService.reason(new ResourceAccessException("connect refused to secret-host")))
                .isEqualTo("upstream unreachable");
        assertThat(IngestService.reason(new IllegalStateException("embed down"))).isEqualTo("sync failed");
    }

    private static TrendingRow row(String pageId, String week, String description) {
        return new TrendingRow(pageId, "a/one", week, 100.0, "Python", "agents",
                "https://github.com/a/one", description, "comment");
    }
}
