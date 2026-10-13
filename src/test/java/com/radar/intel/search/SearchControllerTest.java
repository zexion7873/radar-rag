package com.radar.intel.search;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SearchController.class)
@Import(RowSearch.class)
class SearchControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private VectorStore vectorStore;

    @MockitoBean
    private RecentWeeks recentWeeks;

    static Stream<Arguments> unsafeFilters() {
        return Stream.of("source", "category", "language", "week")
                .flatMap(field -> Stream.of("a'b", "a\"b", "a\\b")
                        .map(value -> Arguments.of(field, value)));
    }

    // A second layer over Spring AI's own escaping: a value no ingested row holds never reaches the store.
    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("unsafeFilters")
    void quoteOrBackslashInAFilterIsRejectedBeforeTheStore(String field, String value) throws Exception {
        search(Map.of("q", "agents", field, value), 400);
        verifyNoInteractions(vectorStore);
    }

    @Test
    void aCleanFilterReachesTheStore() throws Exception {
        search(Map.of("q", "agents", "source", "trending", "week", "2026-09-21", "language", "Python"), 200);
        ArgumentCaptor<SearchRequest> sent = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(sent.capture());
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        assertThat(sent.getValue().getFilterExpression()).isEqualTo(b.and(
                b.and(b.eq("source", "trending"), b.eq("language", "Python")), b.eq("week", "2026-09-21")).build());
    }

    @Test
    void withoutASourceEachSourceIsSearchedUnderTheFilterAndTakenInTurns() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenAnswer(inv -> {
            String filter = String.valueOf(inv.getArgument(0, SearchRequest.class).getFilterExpression());
            return filter.contains("trending")
                    ? List.of(hit("t1", "https://github.com/a/one", 0.9), hit("t2", "https://github.com/b/two", 0.8),
                            hit("t3", "https://github.com/c/three", 0.7))
                    : List.of(hit("b1", "https://x.test/1", 0.95), hit("b2", "https://x.test/2", 0.5));
        });
        mvc.perform(post("/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("q", "agents", "topK", 5, "week", "2026-09-21"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(contains("b1", "t1", "b2", "t2", "t3")));
        ArgumentCaptor<SearchRequest> sent = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, times(2)).similaritySearch(sent.capture());
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        assertThat(sent.getAllValues()).extracting(SearchRequest::getFilterExpression).containsExactly(
                b.and(b.eq("week", "2026-09-21"), b.eq("source", "trending")).build(),
                b.and(b.eq("week", "2026-09-21"), b.eq("source", "blog")).build());
    }

    @Test
    void aCategoryTheQuestionNamesFiltersBothSourcesSoTrendingFillsTheSlots() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenAnswer(inv ->
                String.valueOf(inv.getArgument(0, SearchRequest.class).getFilterExpression()).contains("trending")
                        ? List.of(hit("t1", "https://github.com/a/one", 0.6), hit("t2", "https://github.com/b/two", 0.5))
                        : List.of());
        mvc.perform(post("/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("q", "apps 類有哪些 repo？", "topK", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(contains("t1", "t2")));
        ArgumentCaptor<SearchRequest> sent = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, times(2)).similaritySearch(sent.capture());
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        assertThat(sent.getAllValues()).extracting(SearchRequest::getFilterExpression).containsExactly(
                b.and(b.eq("category", "apps"), b.eq("source", "trending")).build(),
                b.and(b.eq("category", "apps"), b.eq("source", "blog")).build());
        verifyNoInteractions(recentWeeks);
    }

    @Test
    void aCategoryInTheRequestWinsOverOneInTheQuestion() throws Exception {
        search(Map.of("q", "apps 類有哪些 repo？", "category", "infra"), 200);
        ArgumentCaptor<SearchRequest> sent = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, times(2)).similaritySearch(sent.capture());
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        assertThat(sent.getAllValues()).extracting(SearchRequest::getFilterExpression).containsExactly(
                b.and(b.eq("category", "infra"), b.eq("source", "trending")).build(),
                b.and(b.eq("category", "infra"), b.eq("source", "blog")).build());
    }

    @Test
    void aRecentQuestionRanksTheLastWeeksHigherAndReturnsARepoAtItsRecentWeek() throws Exception {
        when(recentWeeks.of("trending")).thenReturn(List.of("2026-09-14", "2026-09-21"));
        when(recentWeeks.of("blog")).thenReturn(List.of());
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenAnswer(inv -> {
            String filter = String.valueOf(inv.getArgument(0, SearchRequest.class).getFilterExpression());
            if (!filter.contains("trending")) {
                return List.of();
            }
            // The recent-weeks search returns only its own weeks; the unrestricted one leads with June.
            return filter.contains("2026-09-21")
                    ? List.of(week("a-sept", "https://github.com/a/one", 0.5, "2026-09-21"),
                            week("c-sept", "https://github.com/c/three", 0.45, "2026-09-14"))
                    : List.of(week("a-june", "https://github.com/a/one", 0.58, "2026-06-18"),
                            week("b-june", "https://github.com/b/two", 0.57, "2026-06-18"),
                            week("a-sept", "https://github.com/a/one", 0.5, "2026-09-21"));
        });
        mvc.perform(post("/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("q", "最近有哪些 repo？", "topK", 3))))
                .andExpect(status().isOk())
                // 0.5 + 0.1 > 0.58 and 0.45 + 0.1 < 0.57: the bonus lifts a near tie, not a clear loser.
                .andExpect(jsonPath("$[*].id").value(contains("a-sept", "b-june", "c-sept")));
        ArgumentCaptor<SearchRequest> sent = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, times(3)).similaritySearch(sent.capture());
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        assertThat(sent.getAllValues().get(1).getFilterExpression()).isEqualTo(
                b.and(b.eq("source", "trending"), b.in("week", "2026-09-14", "2026-09-21")).build());
    }

    @Test
    void underTheRecencyBoostTheSourceWithTheMostSimilarRowStillLeads() throws Exception {
        when(recentWeeks.of("trending")).thenReturn(List.of("2026-09-21"));
        when(recentWeeks.of("blog")).thenReturn(List.of("2026-09-27"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenAnswer(inv -> {
            String filter = String.valueOf(inv.getArgument(0, SearchRequest.class).getFilterExpression());
            boolean recentOnly = filter.contains("2026-09-");
            // Boosted, each source's recent row ranks first: trending's at 0.52 + 0.1, blog's at 0.56 + 0.1.
            // Ordered by those first rows blog would lead; by the most similar row (0.60 > 0.57) trending does.
            if (filter.contains("trending")) {
                return recentOnly ? List.of(week("t-new", "https://github.com/a/new", 0.52, "2026-09-21"))
                        : List.of(week("t-old", "https://github.com/a/old", 0.60, "2026-06-18"));
            }
            return recentOnly ? List.of(week("b-new", "https://x.test/new", 0.56, "2026-09-27"))
                    : List.of(week("b-old", "https://x.test/old", 0.57, "2026-06-20"));
        });
        mvc.perform(post("/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("q", "最近有哪些？", "topK", 4))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(contains("t-new", "b-new", "t-old", "b-old")));
    }

    private static Document week(String id, String url, double score, String week) {
        return Document.builder().id(id).text("t").metadata(Map.of("url", url, "week", week)).score(score).build();
    }

    // The eval harness identifies rows by this id; dropping it would break its id checks silently.
    @Test
    void hitsCarryTheDocumentId() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().id("doc-1").text("t").metadata(Map.of("source", "trending")).score(0.5).build()));
        mvc.perform(post("/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("q", "agents"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("doc-1"));
    }

    @Test
    void aRepoComesBackOnceAtItsBestWeek() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                hit("a-sept", "https://github.com/a/one", 0.9),
                hit("a-june", "https://github.com/a/one", 0.8),
                hit("b", "https://github.com/b/two", 0.7),
                hit("c", "https://x.test/post", 0.6)));
        mvc.perform(post("/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("q", "agents", "source", "trending", "topK", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("a-sept"))
                .andExpect(jsonPath("$[1].id").value("b"));
        ArgumentCaptor<SearchRequest> sent = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(sent.capture());
        assertThat(sent.getValue().getTopK()).isEqualTo(10);
    }

    private static Document hit(String id, String url, double score) {
        return Document.builder().id(id).text("t").metadata(Map.of("url", url)).score(score).build();
    }

    @Test
    void aMissingQueryIsRejected() throws Exception {
        search(Map.of("week", "2026-09-21"), 400);
        verifyNoInteractions(vectorStore);
    }

    private void search(Map<String, Object> body, int expectedStatus) throws Exception {
        mvc.perform(post("/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus));
    }
}
