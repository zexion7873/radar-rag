package com.radar.intel.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.radar.intel.ingest.TrendingIngestService;
import com.radar.intel.notion.TrendingRow;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Shared plumbing for the P3 evals: one disposable pgvector and one fixture ingest for
 * every eval class. Deliberately the singleton-container pattern rather than
 * per-class @Container — Spring caches the application context across test classes,
 * and a per-class container would be stopped underneath that cached context when the
 * next class runs. Ryuk reaps the container when the test JVM exits.
 */
@SpringBootTest
@ActiveProfiles("eval")
public abstract class EvalSupport {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static boolean ingested;

    @Autowired
    protected VectorStore vectorStore;

    public record GoldenQuery(String q, String lang, List<String> relevant) {
    }

    public record JudgeCase(String q) {
    }

    record Corpus(List<TrendingRow> rows) {
    }

    record Golden(List<GoldenQuery> retrieval, List<JudgeCase> judge) {
    }

    @BeforeEach
    void ingestFixturesOnce() {
        if (ingested) {
            return;
        }
        vectorStore.add(fixtureDocuments());
        ingested = true;
    }

    static Golden golden() {
        return read("/eval/golden.json", Golden.class);
    }

    /** Fixture rows mapped through the production ingest mapping (TrendingIngestService.toDocument). */
    static List<Document> fixtureDocuments() {
        return read("/eval/corpus.json", Corpus.class).rows().stream()
                .map(TrendingIngestService::toDocument)
                .filter(Objects::nonNull)
                .toList();
    }

    /** Fixture documents keyed by link, to hand the judge the exact texts a citation points at. */
    static Map<String, Document> corpusByUrl() {
        return fixtureDocuments().stream()
                .collect(Collectors.toMap(d -> (String) d.getMetadata().get("url"), Function.identity()));
    }

    private static <T> T read(String classpath, Class<T> type) {
        try (InputStream in = EvalSupport.class.getResourceAsStream(classpath)) {
            return JSON.readValue(Objects.requireNonNull(in, classpath + " not on classpath"), type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
