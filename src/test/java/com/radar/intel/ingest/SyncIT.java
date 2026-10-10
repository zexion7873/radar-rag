package com.radar.intel.ingest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * /sync from the frozen Notion fixtures into a real pgvector: trending (190 rows, 190 distinct (url, week)
 * pairs over 111 urls) and blog (645 rows). Counts come from SQL, because /sync reports rows ingested,
 * not the table's size.
 */
@SpringBootTest
class SyncIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg16").asCompatibleSubstituteFor("postgres"));

    static final WireMockServer NOTION =
            new WireMockServer(wireMockConfig().dynamicPort().http2PlainDisabled(true));

    static {
        POSTGRES.start();
        NOTION.start();
    }

    private static final String TRENDING = "f67aaa24-d5f2-415c-9358-c7d9d2f9713e";
    private static final String BLOG = "d8e442b5-17c1-4e6f-a665-feb49d6e3099";
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("radar.notion.base-url", NOTION::baseUrl);
        registry.add("radar.notion.token", () -> "unused");
        registry.add("radar.notion.trending-data-source", () -> TRENDING);
        registry.add("radar.notion.blog-data-source", () -> BLOG);
    }

    @MockitoBean
    EmbeddingModel embeddingModel;

    @Autowired
    IngestService ingest;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void fullSyncFromTheFixtures() throws IOException {
        float[] vector = new float[384];
        vector[0] = 1f;
        when(embeddingModel.dimensions()).thenReturn(384);
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        when(embeddingModel.embed(anyList(), any(), any())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(0)).stream().map(d -> vector).toList());
        NOTION.resetAll();
        serve(TRENDING, pages("trending"));
        serve(BLOG, pages("blog"));

        IngestService.SyncResult result = ingest.sync();

        assertThat(result.failed()).isEmpty();
        assertThat(result.sources()).containsExactly(Map.entry("trending", 190), Map.entry("blog", 645));
        assertThat(result.ingested()).isEqualTo(835);
    }

    @Test
    void everyWeekOfARepoIsItsOwnRow() {
        Map<String, Object> counts = jdbc.queryForMap(
                "select count(*) as rows, count(distinct id) as ids,"
                        + " count(distinct metadata->>'url') as urls from vector_store"
                        + " where metadata->>'source' = 'trending'");

        assertThat(counts).containsEntry("rows", 190L).containsEntry("ids", 190L).containsEntry("urls", 111L);
    }

    @Test
    void aRowDeletedInNotionLeavesNoGhostAfterResync() throws IOException {
        ArrayNode trending = pages("trending");
        trending.remove(0);
        serve(TRENDING, trending);

        assertThat(ingest.sync().sources()).containsEntry("trending", 189).containsEntry("blog", 645);
        assertThat(rows("trending")).isEqualTo(189L);
    }

    @Test
    void aFailedEmbedRollsTheDeleteBack() {
        when(embeddingModel.embed(anyList(), any(), any())).thenThrow(new IllegalStateException("embed down"));

        IngestService.SyncResult result = ingest.sync();

        assertThat(result.failed()).containsEntry("trending", "sync failed").containsEntry("blog", "sync failed");
        assertThat(rows("trending")).isEqualTo(190L);
        assertThat(rows("blog")).isEqualTo(645L);
    }

    @Test
    void aFailingSourceLeavesTheOthersSyncedAndItsOwnRowsInPlace() throws IOException {
        ArrayNode trending = pages("trending");
        trending.remove(0);
        serve(TRENDING, trending);
        NOTION.stubFor(get("/data_sources/" + BLOG).willReturn(notFound()));
        NOTION.stubFor(get("/databases/" + BLOG).willReturn(notFound()));

        IngestService.SyncResult result = ingest.sync();

        assertThat(result.sources()).containsExactly(Map.entry("trending", 189));
        assertThat(result.failed()).containsExactly(Map.entry("blog", "upstream 404"));
        assertThat(rows("trending")).isEqualTo(189L);
        assertThat(rows("blog")).isEqualTo(645L);
    }

    private long rows(String source) {
        return jdbc.queryForObject(
                "select count(*) from vector_store where metadata->>'source' = ?", Long.class, source);
    }

    private static ArrayNode pages(String source) throws IOException {
        JsonNode fixture = JSON.readTree(Path.of("evals/fixtures/" + source + ".json").toFile());
        return (ArrayNode) fixture.get("pages");
    }

    private static void serve(String dataSource, ArrayNode pages) {
        NOTION.stubFor(get("/data_sources/" + dataSource).willReturn(okJson("{\"id\":\"" + dataSource + "\"}")));
        ObjectNode page = JSON.createObjectNode();
        page.set("results", pages);
        page.put("has_more", false);
        page.putNull("next_cursor");
        NOTION.stubFor(post("/data_sources/" + dataSource + "/query").willReturn(okJson(page.toString())));
    }
}
