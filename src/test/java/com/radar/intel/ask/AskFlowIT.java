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
import org.mockito.InOrder;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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

    @MockitoBean
    AskBudget budget;

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
                        Map.of("source", "trending", "repo", "o/repo-" + i, "url", "https://github.com/o/repo-" + i,
                                "week", "2026-09-21")))
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
        verifyNoInteractions(budget);
        assertThat(ANTHROPIC.findAll(postRequestedFor(urlEqualTo("/v1/messages")))).isEmpty();
    }

    @Test
    void aSpentDailyLimitIs429AfterTheBotCheckAndNeverReachesTheModel() throws Exception {
        ErrorResponseException refused = new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS);
        refused.getHeaders().set(HttpHeaders.RETRY_AFTER, "864");
        doThrow(refused).when(budget).spend();

        mvc.perform(post("/ask").contentType(MediaType.APPLICATION_JSON).header("X-Turnstile-Token", "t")
                        .content("{\"q\":\"agents\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "864"));
        mvc.perform(post("/ask").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .header("X-Turnstile-Token", "t").content("{\"q\":\"agents\"}"))
                .andExpect(status().isTooManyRequests());

        InOrder order = inOrder(turnstile, budget);
        order.verify(turnstile).check("t");
        order.verify(budget).spend();
        assertThat(ANTHROPIC.findAll(postRequestedFor(urlEqualTo("/v1/messages")))).isEmpty();
    }

    @Test
    void aQuestionPastAThousandCharactersIs400AndNeverReachesTheBotCheckOrTheModel() throws Exception {
        mvc.perform(post("/ask").contentType(MediaType.APPLICATION_JSON).header("X-Turnstile-Token", "t")
                        .content("{\"q\":\"" + "a".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(turnstile);
        assertThat(ANTHROPIC.findAll(postRequestedFor(urlEqualTo("/v1/messages")))).isEmpty();
    }

    @Test
    void theRootRedirectsToGithubRadarUisAskPageAndConfigStillAnswers() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "https://whyisthistrending.vercel.app/ask"));
        mvc.perform(get("/index.html")).andExpect(status().isNotFound());
        mvc.perform(get("/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnstileSiteKey").value("1x00000000000000000000AA"));
    }

    @Test
    void theUiOriginMayPostAskFromTheBrowserAndNoOtherOriginOrPathMay() throws Exception {
        mvc.perform(options("/ask").header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-turnstile-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Methods", "POST"));
        mvc.perform(options("/ask").header("Origin", "https://evil.test")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/search").header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST"))
                // Answered as a plain OPTIONS: without the allow-origin header the browser refuses.
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));

        stubMessages(200, """
                {"id":"msg_c","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"text","text":"ok"}],
                 "stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}""");
        mvc.perform(post("/ask").header("Origin", "http://localhost:3000").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"q\":\"agents\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        mvc.perform(post("/ask").header("Origin", "https://evil.test").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"q\":\"agents\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anyAcceptOtherThanEventStreamGetsTheJsonAnswer() throws Exception {
        stubMessages(200, """
                {"id":"msg_j","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"text","text":"ok"}],
                 "stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}""");

        for (String accept : List.of("*/*", "application/json", "application/json, */*")) {
            mvc.perform(post("/ask").contentType(MediaType.APPLICATION_JSON).header("Accept", accept)
                            .content("{\"q\":\"agents\"}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/json"))
                    .andExpect(jsonPath("$.answer").value("ok"));
        }
    }

    @Test
    void streamsSourcesThenDeltasThenDoneCarryingTheWholeAnswer() throws Exception {
        stubStream(textBlock("Grounded ", "answer."), citation(2, "first"), "end_turn");

        List<String[]> events = askStream();

        assertThat(events).extracting(e -> e[0]).containsExactly("sources", "delta", "delta", "done");
        assertThat(JSON.readTree(events.get(0)[1])).hasSize(5);
        assertThat(JSON.readTree(events.get(1)[1]).path("text").asString()).isEqualTo("Grounded ");
        JsonNode done = JSON.readTree(events.get(3)[1]);
        assertThat(done.path("answer").asString()).isEqualTo("Grounded answer.");
        assertThat(done.path("citations")).hasSize(1);
        assertThat(done.path("citations").get(0).path("citedText").get(0).asString()).isEqualTo("first");
        assertThat(done.path("usage").path("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(done.path("usage").path("inputTokens").asInt()).isEqualTo(10);
        assertThat(done.path("usage").path("outputTokens").asInt()).isEqualTo(5);

        JsonNode sent = JSON.readTree(onlyRequest().getBodyAsString());
        assertThat(sent.path("stream").asBoolean()).isTrue();
        assertThat(sent.path("output_config").path("effort").asString()).isEqualTo("medium");
    }

    @Test
    void aRefusalAfterStreamedTextEndsInErrorAndNeverDone() throws Exception {
        stubStream(textBlock("Here is how to "), "", "refusal");

        List<String[]> events = askStream();

        assertThat(events).extracting(e -> e[0]).containsExactly("sources", "delta", "error");
        assertThat(JSON.readTree(events.get(2)[1]).path("status").asInt()).isEqualTo(502);
    }

    @Test
    void aStreamWithNoTextEndsInError() throws Exception {
        stubStream("", "", "end_turn");

        assertThat(askStream()).extracting(e -> e[0]).containsExactly("sources", "error");
    }

    @ParameterizedTest
    @CsvSource({
            "400, 502, 1",
            "529, 503, 2",
    })
    void anUpstreamFailureAfterSourcesIsAnErrorEventWithoutTheUpstreamBody(
            int upstream, int expected, int attempts) throws Exception {
        stubMessages(upstream, "{\"type\":\"error\",\"error\":{\"message\":\"" + UPSTREAM_SECRET + "\"}}");

        List<String[]> events = askStream();

        assertThat(events).extracting(e -> e[0]).containsExactly("sources", "error");
        assertThat(JSON.readTree(events.get(1)[1]).path("status").asInt()).isEqualTo(expected);
        assertThat(events.get(1)[1]).doesNotContain(UPSTREAM_SECRET);
        ANTHROPIC.verify(attempts, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void aFailedBotCheckOnTheStreamIsStill403AndNeverReachesTheModel() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "bot check failed"))
                .when(turnstile).check("bad-token");

        mvc.perform(post("/ask").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .header("X-Turnstile-Token", "bad-token").content("{\"q\":\"agents\"}"))
                .andExpect(status().isForbidden());

        assertThat(ANTHROPIC.findAll(postRequestedFor(urlEqualTo("/v1/messages")))).isEmpty();
    }

    /** Each event as {name, data}, in order. */
    private List<String[]> askStream() throws Exception {
        MvcResult started = mvc.perform(post("/ask").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content("{\"q\":\"agents\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("text/event-stream")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String[]> events = new ArrayList<>();
        for (String frame : body.split("\n\n")) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : frame.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length());
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()));
                }
            }
            if (name != null) {
                events.add(new String[] {name, data.toString()});
            }
        }
        return events;
    }

    /** A text content block's events, one text delta per chunk, or none for an empty answer. */
    private static String textBlock(String... chunks) {
        if (chunks.length == 0 || chunks[0].isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(sse("content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
        for (String chunk : chunks) {
            sb.append(sse("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                    + "\"delta\":{\"type\":\"text_delta\",\"text\":\"" + chunk + "\"}}"));
        }
        return sb.toString();
    }

    private static String citation(int documentIndex, String citedText) {
        return sse("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                + "{\"type\":\"citations_delta\",\"citation\":{\"type\":\"char_location\",\"cited_text\":\""
                + citedText + "\",\"document_index\":" + documentIndex + ",\"document_title\":\"t\","
                + "\"start_char_index\":0,\"end_char_index\":5}}}");
    }

    /** Anthropic's streaming wire format around the given content block events. */
    private static void stubStream(String textBlock, String citations, String stopReason) {
        String body = sse("message_start", """
                {"type":"message_start","message":{"id":"msg_s","type":"message","role":"assistant",\
                "model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,\
                "usage":{"input_tokens":10,"output_tokens":1}}}""")
                + textBlock + citations
                + (textBlock.isEmpty() ? "" : sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"))
                + sse("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason
                        + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":5}}")
                + sse("message_stop", "{\"type\":\"message_stop\"}");
        ANTHROPIC.stubFor(WireMock.post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/event-stream").withBody(body)));
    }

    private static String sse(String event, String data) {
        return "event: " + event + "\ndata: " + data + "\n\n";
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
