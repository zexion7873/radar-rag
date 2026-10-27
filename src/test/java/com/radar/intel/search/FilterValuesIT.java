package com.radar.intel.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
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

/**
 * RowSearch filters on url, repo and week values it read back from the table, with no quote check of its
 * own: Spring AI's pgvector converter is the only escaping between a Notion value and the jsonpath.
 */
@SpringBootTest(properties = "radar.notion.token=unused")
class FilterValuesIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg16").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    private static final String[] VALUES = {
        "https://x.test/it's",
        "https://x.test/\"q\"",
        "https://x.test/a\\b",
        "https://x.test/' || true || '",
        "https://x.test/\" || @ != \"",
    };

    @MockitoBean
    EmbeddingModel embeddingModel;

    @Autowired
    VectorStore vectorStore;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void oneRowPerValueAndAPlainOne() {
        float[] vector = new float[384];
        vector[0] = 1f;
        when(embeddingModel.dimensions()).thenReturn(384);
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        when(embeddingModel.embed(anyList(), any(), any())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(0)).stream().map(d -> vector).toList());
        jdbc.update("delete from vector_store");
        for (int i = 0; i < VALUES.length; i++) {
            vectorStore.add(List.of(row("00000000-0000-0000-0000-00000000000" + i, VALUES[i])));
        }
        vectorStore.add(List.of(row("00000000-0000-0000-0000-000000000009", "https://x.test/plain")));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void aValueWithQuotesOrBackslashesMatchesItsOwnRowAndNoOther(int i) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        for (String field : List.of("url", "repo")) {
            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder().query("q").topK(10)
                    .filterExpression(b.in(field, List.<Object>of(VALUES[i])).build()).build());
            assertThat(hits).extracting(d -> d.getMetadata().get(field)).containsExactly(VALUES[i]);
        }
    }

    private static Document row(String id, String value) {
        return Document.builder().id(id).text(value).metadata(Map.of(
                "source", "blog", "url", value, "repo", value, "week", "2026-10-05")).build();
    }
}
