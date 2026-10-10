package com.radar.intel.ask;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /ask end to end against a real pgvector, with WireMock standing in for Anthropic. Pins the
 * request shape Opus 5.5 accepts and the status each upstream failure maps to.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AskFlowIT {

    // One container for the JVM: Spring caches this context across test classes, and a
    // per-class container would be stopped underneath it.
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg16").asCompatibleSubstituteFor("postgres"));

    static final WireMockServer ANTHROPIC = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        POSTGRES.start();
        ANTHROPIC.start();
    }

    private static final String UPSTREAM_SECRET = "upstream-body-must-not-leak";
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.anthropic.base-url", ANTHROPIC::baseUrl);
        registry.add("spring.ai.anthropic.api-key", () -> "test-key");
        registry.add("radar.notion.token", () -> "unused");
    }

    @MockitoBean
    EmbeddingModel embeddingModel;

    @MockitoBean
    TurnstileVerifier turnstile;

    @Autowired
    VectorStore vectorStore;

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void seedSixRows() {
        float[] vector = new float[384];
        vector[0] = 1f;
        when(embeddingModel.dimensions()).thenReturn(384);
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        when(embeddingModel.embed(anyList(), any(), any())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(0)).stream().map(d -> vector).toList());
        // Fixed ids, so re-seeding before each test upserts instead of growing the table.
        vectorStore.add(IntStream.range(0, 6)
                .mapToObj(i -> new Document(UUID.nameUUIDFromBytes(("row-" + i).getBytes()).toString(),
                        "repo-" + i + " is a coding agent",
                        Map.of("repo", "o/repo-" + i, "url", "https://github.com/o/repo-" + i, "week", "2026-09-21")))
                .toList());
        ANTHROPIC.resetAll();
    }

    @Test
    void answersWithTheTextBlocksOnlyAndSendsNoSamplingParameter() throws Exception {
        stubMessages(200, """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"thinking","thinking":"","signature":"sig"},
                            {"type":"text","text":"Grounded "},{"type":"text","text":"answer."}],
                 "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}""");

        ask().andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Grounded answer."))
                .andExpect(jsonPath("$.citations.length()").value(0))
                .andExpect(jsonPath("$.sources.length()").value(5))
                .andExpect(jsonPath("$.usage.model").value("claude-opus-5-5"))
                .andExpect(jsonPath("$.usage.inputTokens").value(10))
                .andExpect(jsonPath("$.usage.outputTokens").value(5));

        JsonNode sent = JSON.readTree(onlyRequest().getBodyAsString());
        assertThat(sent.has("temperature")).isFalse();
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(16000);
        assertThat(sent.path("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(sent.path("output_config").path("effort").asString()).isEqualTo("medium");
    }

    @Test
    void returnsOnlyTheCitedRowsMappedByDocumentIndex() throws Exception {
        stubMessages(200, """
                {"id":"msg_3","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"text","text":"Try it.","citations":[
                     {"type":"char_location","cited_text":"first","document_index":2,
                      "document_title":"t","start_char_index":0,"end_char_index":5},
                     {"type":"char_location","cited_text":"second","document_index":0,
                      "document_title":"t","start_char_index":0,"end_char_index":6},
                     {"type":"char_location","cited_text":"third","document_index":2,
                      "document_title":"t","start_char_index":6,"end_char_index":11},
                     {"type":"char_location","cited_text":"first","document_index":2,
                      "document_title":"t","start_char_index":0,"end_char_index":5}]}],
                 "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}""");

        String body = ask().andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // Every retrieved row goes out as a citable document titled "repo week".
        List<JsonNode> documents = new ArrayList<>();
        JSON.readTree(onlyRequest().getBodyAsString()).path("messages").get(0).path("content")
                .forEach(block -> {
                    if ("document".equals(block.path("type").asString())) {
                        documents.add(block);
                    }
                });
        assertThat(documents).hasSize(5)
                .allSatisfy(d -> {
                    assertThat(d.path("citations").path("enabled").asBoolean()).isTrue();
                    assertThat(d.path("title").asString()).endsWith(" 2026-09-21");
                });

        JsonNode citations = JSON.readTree(body).path("citations");
        assertThat(citations).hasSize(2);
        assertThat(citations.get(0).path("repo").asString() + " 2026-09-21")
                .isEqualTo(documents.get(2).path("title").asString());
        assertThat(citations.get(0).path("citedText")).extracting(JsonNode::asString)
                .containsExactly("first", "third");
        assertThat(citations.get(1).path("repo").asString() + " 2026-09-21")
                .isEqualTo(documents.get(0).path("title").asString());
        assertThat(citations.get(1).path("citedText")).extracting(JsonNode::asString)
                .containsExactly("second");
    }

    @Test
    void aRefusalIsA502NotAnEmptyAnswer() throws Exception {
        stubMessages(200, """
                {"id":"msg_2","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[],"stop_reason":"refusal","usage":{"input_tokens":10,"output_tokens":0}}""");

        ask().andExpect(status().isBadGateway());
    }

    @ParameterizedTest
    @CsvSource({
            "400, 502, 1",  // bad request: a client error, never retried
            "401, 502, 1",  // bad key
            "429, 503, 2",  // rate limited: retried once, then "try later"
            "529, 503, 2",  // overloaded: retried once, then "try later"
    })
    void upstreamFailuresMapToGatewayStatusesWithoutEchoingTheUpstreamBody(
            int upstream, int expected, int attempts) throws Exception {
        stubMessages(upstream, "{\"type\":\"error\",\"error\":{\"message\":\"" + UPSTREAM_SECRET + "\"}}");

        MvcResult result = ask().andExpect(status().is(expected)).andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(UPSTREAM_SECRET);
        ANTHROPIC.verify(attempts, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void aFailedBotCheckIs403AndNeverReachesTheModel() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "bot check failed"))
                .when(turnstile).check("bad-token");

        mvc.perform(post("/ask").contentType(MediaType.APPLICATION_JSON).header("X-Turnstile-Token", "bad-token")
                        .content("{\"q\":\"agents\"}"))
                .andExpect(status().isForbidden());

        verify(turnstile).check("bad-token");
        assertThat(ANTHROPIC.findAll(postRequestedFor(urlEqualTo("/v1/messages")))).isEmpty();
    }

    @Test
    void theRootServesTheAskPageAndConfigItsSiteKey() throws Exception {
        mvc.perform(get("/")).andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>Ask the radar</title>")));
        mvc.perform(get("/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnstileSiteKey").value("1x00000000000000000000AA"));
    }

    private ResultActions ask() throws Exception {
        return mvc.perform(post("/ask").contentType(MediaType.APPLICATION_JSON)
                .content("{\"q\":\"最近有哪些 coding agent 相關的 repo？\"}"));
    }

    private static void stubMessages(int status, String body) {
        ANTHROPIC.stubFor(WireMock.post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static LoggedRequest onlyRequest() {
        List<LoggedRequest> requests = ANTHROPIC.findAll(postRequestedFor(urlEqualTo("/v1/messages")));
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }
}
