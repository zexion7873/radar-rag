package com.radar.intel.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** KeywordIndex's digest and row queries against a real pgvector table, where the unit tests mock it away. */
@SpringBootTest(properties = "radar.notion.token=unused")
class KeywordIndexIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg16").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    private static final String CHIP = "00000000-0000-0000-0000-000000000001";

    @MockitoBean
    EmbeddingModel embeddingModel;

    @Autowired
    VectorStore vectorStore;

    @Autowired
    KeywordIndex keywordIndex;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void blogAndTrendingRows() {
        float[] vector = new float[384];
        vector[0] = 1f;
        when(embeddingModel.dimensions()).thenReturn(384);
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        when(embeddingModel.embed(anyList(), any(), any())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(0)).stream().map(d -> vector).toList());
        jdbc.update("delete from vector_store");
        vectorStore.add(List.of(
                row(CHIP, "OpenAI 發表推論晶片", "blog", "https://x.test/chip"),
                row("00000000-0000-0000-0000-000000000002", "AI agent 框架", "blog", "https://x.test/agent"),
                row("00000000-0000-0000-0000-000000000003", "晶片設計工具", "trending", "https://github.com/a/chip")));
    }

    @Test
    void aSourcesRowsAreScoredAndMappedToTheirUrls() {
        // The chip post shares 推論, 論晶 and 晶片; the agent post only "ai"; the trending row is another source.
        KeywordIndex.Match match = keywordIndex.match("blog", "AI 推論晶片的文章？", 5);

        assertThat(match.score()).containsOnlyKeys(CHIP, "00000000-0000-0000-0000-000000000002");
        assertThat(match.urls()).containsExactly("https://x.test/chip", "https://x.test/agent");
    }

    // A /sync on another instance changes the table without this instance's IngestService; the digest sees it.
    @Test
    void aRowRewrittenUnderTheSameIdIsSeenOnTheNextQuestion() {
        assertThat(keywordIndex.match("blog", "量子電腦", 5)).isEqualTo(KeywordIndex.Match.NONE);

        jdbc.update("update vector_store set content = ? where id = ?::uuid", "量子電腦的新進展", CHIP);

        assertThat(keywordIndex.match("blog", "量子電腦", 5).urls()).containsExactly("https://x.test/chip");
    }

    private static Document row(String id, String text, String source, String url) {
        return Document.builder().id(id).text(text).metadata(Map.of("source", source, "url", url)).build();
    }
}
