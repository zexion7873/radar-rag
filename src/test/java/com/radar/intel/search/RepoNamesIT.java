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

/** RepoNames' lookup against a real pgvector table: the question's spelling, the archive's answer. */
@SpringBootTest(properties = "radar.notion.token=unused")
class RepoNamesIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg16").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    @MockitoBean
    EmbeddingModel embeddingModel;

    @Autowired
    VectorStore vectorStore;

    @Autowired
    RepoNames repoNames;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void trendingAndBlogRows() {
        float[] vector = new float[384];
        vector[0] = 1f;
        when(embeddingModel.dimensions()).thenReturn(384);
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        when(embeddingModel.embed(anyList(), any(), any())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(0)).stream().map(d -> vector).toList());
        jdbc.update("delete from vector_store");
        vectorStore.add(List.of(
                trending("00000000-0000-0000-0000-000000000001", "mattpocock/skills", "2026-10-05"),
                trending("00000000-0000-0000-0000-000000000002", "mattpocock/skills", "2026-10-12"),
                trending("00000000-0000-0000-0000-000000000003", "tester-army/e2e", "2026-10-12"),
                Document.builder().id("00000000-0000-0000-0000-000000000004").text("a/post")
                        .metadata(Map.of("source", "blog", "title", "a/post", "url", "https://x.test/a")).build()));
    }

    @Test
    void aNamedRepoComesBackOnceInTheArchivesSpelling() {
        assertThat(repoNames.in("「mattpocock/skills」是做什麼的？為什麼會紅？")).containsExactly("mattpocock/skills");
        assertThat(repoNames.in("What is MattPocock/Skills.")).containsExactly("mattpocock/skills");
        assertThat(repoNames.in("比較 https://github.com/tester-army/e2e 和 mattpocock/skills"))
                .containsExactly("mattpocock/skills", "tester-army/e2e");
    }

    // A blog title is not a repo, and a pair the archive lacks is no lookup result.
    @Test
    void onlyTrendingReposTheArchiveHoldsCount() {
        assertThat(repoNames.in("a/post 和 nobody/nothing 是什麼？")).isEmpty();
        assertThat(repoNames.in("有能剪片的套件嗎？")).isEmpty();
    }

    private static Document trending(String id, String repo, String week) {
        return Document.builder().id(id).text(repo).metadata(Map.of(
                "source", "trending", "repo", repo, "week", week, "url", "https://github.com/" + repo)).build();
    }
}
