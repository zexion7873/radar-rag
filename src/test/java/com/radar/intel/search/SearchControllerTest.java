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
import static org.mockito.ArgumentMatchers.any;
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

    static Stream<Arguments> unsafeFilters() {
        return Stream.of("source", "category", "language", "week")
                .flatMap(field -> Stream.of("a'b", "a\"b", "a\\b")
                        .map(value -> Arguments.of(field, value)));
    }

    // Spring AI 1.0.0's pgvector filter converter splices string values into SQL unescaped,
    // so this 400 is the only thing between a filter value and the query.
    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("unsafeFilters")
    void quoteOrBackslashInAFilterIsRejectedBeforeTheStore(String field, String value) throws Exception {
        search(Map.of("q", "agents", field, value), 400);
        verifyNoInteractions(vectorStore);
    }

    @Test
    void aCleanFilterReachesTheStore() throws Exception {
        search(Map.of("q", "agents", "week", "2026-09-21", "language", "Python"), 200);
        ArgumentCaptor<SearchRequest> sent = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(sent.capture());
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        assertThat(sent.getValue().getFilterExpression())
                .isEqualTo(b.and(b.eq("language", "Python"), b.eq("week", "2026-09-21")).build());
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
                        .content(JSON.writeValueAsString(Map.of("q", "agents", "topK", 2))))
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
