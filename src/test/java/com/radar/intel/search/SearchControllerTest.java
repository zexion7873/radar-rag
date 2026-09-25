package com.radar.intel.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SearchController.class)
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
