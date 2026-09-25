package com.radar.intel.notion;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The first-page stub answers has_more=true forever, so a client that drops start_cursor
// loops instead of failing.
@Timeout(10)
@WireMockTest
class NotionClientTest {

    private static final String DS = "ds-1";

    @Test
    void fetchTrendingFollowsTheCursorAcrossPages(WireMockRuntimeInfo wm) {
        stubFor(get("/data_sources/" + DS).willReturn(okJson("{\"id\":\"" + DS + "\"}")));
        stubFor(post("/data_sources/" + DS + "/query")
                .withRequestBody(equalToJson("{\"page_size\":100}"))
                .willReturn(okJson(page(true, "\"c2\"", row("a/one", "2026-09-14")))));
        stubFor(post("/data_sources/" + DS + "/query")
                .withRequestBody(equalToJson("{\"page_size\":100,\"start_cursor\":\"c2\"}"))
                .willReturn(okJson(page(false, "null", row("b/two", "2026-09-21")))));

        List<TrendingRow> rows = client(wm).fetchTrending();

        assertThat(rows).containsExactly(
                new TrendingRow("a/one", "2026-09-14", 1234.0, "Python", "agents",
                        "https://github.com/a/one", "desc a/one", "comment a/one"),
                new TrendingRow("b/two", "2026-09-21", 1234.0, "Python", "agents",
                        "https://github.com/b/two", "desc b/two", "comment b/two"));
        verify(2, postRequestedFor(urlEqualTo("/data_sources/" + DS + "/query"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .withHeader("Notion-Version", equalTo("2025-09-03")));
    }

    // A stub that skips GET /data_sources/{id} still works, through this fallback, which is
    // why a Notion stand-in must answer it: otherwise failures surface one call later.
    @Test
    void aDatabaseIdResolvesThroughTheLegacyEndpoint(WireMockRuntimeInfo wm) {
        stubFor(get("/data_sources/" + DS).willReturn(aResponse().withStatus(404)));
        stubFor(get("/databases/" + DS)
                .willReturn(okJson("{\"data_sources\":[{\"id\":\"ds-real\"}]}")));
        stubFor(post("/data_sources/ds-real/query")
                .willReturn(okJson(page(false, "null", row("a/one", "2026-09-21")))));

        assertThat(client(wm).fetchTrending()).extracting(TrendingRow::repo).containsExactly("a/one");
    }

    @Test
    void aBlankBaseUrlFailsAtBindingInsteadOfAtTheFirstSync() {
        assertThatThrownBy(() -> new NotionProperties("test-token", " ", "2025-09-03", DS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("radar.notion.base-url");
    }

    private static NotionClient client(WireMockRuntimeInfo wm) {
        return new NotionClient(new NotionProperties("test-token", wm.getHttpBaseUrl(), "2025-09-03", DS));
    }

    private static String page(boolean hasMore, String nextCursorJson, String... rows) {
        return """
                {"results": [%s], "has_more": %s, "next_cursor": %s}
                """.formatted(String.join(",", rows), hasMore, nextCursorJson);
    }

    private static String row(String repo, String week) {
        return """
                {"properties": {
                  "Repo": {"type": "title", "title": [{"plain_text": "%1$s"}]},
                  "Week": {"type": "date", "date": {"start": "%2$s"}},
                  "Stars/wk": {"type": "number", "number": 1234},
                  "Language": {"type": "rich_text", "rich_text": [{"plain_text": "Python"}]},
                  "Category": {"type": "select", "select": {"name": "agents"}},
                  "Link": {"type": "url", "url": "https://github.com/%1$s"},
                  "Description": {"type": "rich_text", "rich_text": [{"plain_text": "desc %1$s"}]},
                  "Comment": {"type": "rich_text", "rich_text": [{"plain_text": "comment %1$s"}]}
                }}
                """.formatted(repo, week);
    }
}
