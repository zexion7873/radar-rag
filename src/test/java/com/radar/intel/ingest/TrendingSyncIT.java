package com.radar.intel.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * /sync from the frozen Notion fixture (190 rows: 190 distinct (url, week) pairs over 111 urls) into a
 * real pgvector. Counts come from SQL, because /sync reports rows ingested, not the table's size.
 */
@SpringBootTest
class TrendingSyncIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg16").asCompatibleSubstituteFor("postgres"));

    static final WireMockServer NOTION =
            new WireMockServer(wireMockConfig().dynamicPort().http2PlainDisabled(true));

    static {
        POSTGRES.start();
        NOTION.start();
    }

    private static final String DATA_SOURCE = "f67aaa24-d5f2-415c-9358-c7d9d2f9713e";
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("radar.notion.base-url", NOTION::baseUrl);
        registry.add("radar.notion.token", () -> "unused");
        registry.add("radar.notion.trending-data-source", () -> DATA_SOURCE);
    }

    @MockitoBean
    EmbeddingModel embeddingModel;

    @Autowired
    TrendingIngestService ingest;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void fullSyncFromTheFixture() throws IOException {
        float[] vector = new float[384];
        vector[0] = 1f;
        when(embeddingModel.dimensions()).thenReturn(384);
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        when(embeddingModel.embed(anyList(), any(), any())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(0)).stream().map(d -> vector).toList());
        serve(fixturePages());
        assertThat(ingest.sync()).isEqualTo(190);
    }

    @Test
    void everyWeekOfARepoIsItsOwnRow() {
        Map<String, Object> counts = jdbc.queryForMap(
                "select count(*) as rows, count(distinct id) as ids,"
                        + " count(distinct metadata->>'url') as urls from vector_store");

        assertThat(counts).containsEntry("rows", 190L).containsEntry("ids", 190L).containsEntry("urls", 111L);
    }

    @Test
    void aRowDeletedInNotionLeavesNoGhostAfterResync() throws IOException {
        ArrayNode pages = fixturePages();
        pages.remove(0);
        serve(pages);

        assertThat(ingest.sync()).isEqualTo(189);
        assertThat(rowCount()).isEqualTo(189L);
    }

    @Test
    void aFailedEmbedRollsTheDeleteBack() {
        when(embeddingModel.embed(anyList(), any(), any())).thenThrow(new IllegalStateException("embed down"));

        assertThatThrownBy(ingest::sync).hasMessage("embed down");
        assertThat(rowCount()).isEqualTo(190L);
    }

    private long rowCount() {
        return jdbc.queryForObject("select count(*) from vector_store", Long.class);
    }

    private static ArrayNode fixturePages() throws IOException {
        JsonNode fixture = JSON.readTree(Path.of("evals/fixtures/trending.json").toFile());
        return (ArrayNode) fixture.get("pages");
    }

    private static void serve(ArrayNode pages) {
        NOTION.resetAll();
        NOTION.stubFor(get("/data_sources/" + DATA_SOURCE).willReturn(okJson("{\"id\":\"" + DATA_SOURCE + "\"}")));
        ObjectNode page = JSON.createObjectNode();
        page.set("results", pages);
        page.put("has_more", false);
        page.putNull("next_cursor");
        NOTION.stubFor(post("/data_sources/" + DATA_SOURCE + "/query").willReturn(okJson(page.toString())));
    }
}
