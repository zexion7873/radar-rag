package com.radar.intel.ask;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.OutputConfig;
import com.radar.intel.search.RowSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicCitationDocument;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Grounded Q&A over the radar archive. Retrieve relevant rows from pgvector, send them to Claude
 * as citation documents, and return the rows Claude cites along with the text it cited.
 */
@RestController
public class AskController {

    private static final Logger log = LoggerFactory.getLogger(AskController.class);

    private static final String SYSTEM = """
            You are the analyst for a GitHub "radar" that tracks trending AI/agent repositories and
            blog posts. Answer the question using only the retrieved radar context. Synthesise rather
            than restate: say in your own words what each relevant source contributes to this
            question and how the sources differ. Do not copy a source's description; the reader sees
            the cited passages beside your answer. Be concise and specific, naming the relevant
            repositories or posts. If the context does not cover the question, say so plainly rather
            than guessing.""";

    private final ChatClient chatClient;
    private final RowSearch rowSearch;
    private final OutputConfig.Effort effort;
    private final TurnstileVerifier turnstile;

    /**
     * {@code radar.ask.effort} is pinned so a model default change cannot move cost or quality; the
     * LLM gate measures medium, and the public prod path runs low.
     */
    public AskController(ChatClient.Builder chatClientBuilder, RowSearch rowSearch,
            @Value("${radar.ask.effort:medium}") String effort, TurnstileVerifier turnstile) {
        this.turnstile = turnstile;
        this.effort = OutputConfig.Effort.of(effort);
        // Throws on an unknown value, which the SDK would otherwise send to the API on every /ask.
        this.effort.known();
        this.chatClient = chatClientBuilder.defaultSystem(SYSTEM).build();
        this.rowSearch = rowSearch;
    }

    public record AskRequest(String q) {
    }

    /** A row the answer was grounded on; a trending row names its repo, a blog row its title. */
    public record Source(String id, String source, String repo, String title, String url, String week,
            Double score) {
    }

    /** A retrieved row the answer cites, with each distinct passage cited from it. */
    public record Citation(String id, String source, String repo, String title, String url, String week,
            Double score, List<String> citedText) {
    }

    /** Token counts of the model call; output tokens include thinking. */
    public record Usage(String model, Integer inputTokens, Integer outputTokens) {
    }

    public record AskResponse(String answer, List<Citation> citations, List<Source> sources, Usage usage) {
    }

    /** A piece of the answer as the model writes it. */
    public record Delta(String text) {
    }

    /** The last event of a streamed answer that succeeded; with {@code sources} it is an {@link AskResponse}. */
    public record Done(String answer, List<Citation> citations, Usage usage) {
    }

    /** The last event of a streamed answer that failed after the stream started. */
    public record StreamError(int status, String message) {
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest req,
            @RequestHeader(value = "X-Turnstile-Token", required = false) String turnstileToken) {
        List<Document> docs = retrieve(req, turnstileToken);
        ChatResponse resp = prompt(req, docs).call().chatResponse();

        List<Generation> generations = resp.getResults();
        // A refusal is an HTTP 200: before any text Spring AI 2.0.1 returns no Generation, after some a
        // "refusal" finish reason. Without this check both read as an empty or partial answer.
        if (generations.isEmpty()
                || generations.stream().anyMatch(g -> "refusal".equals(g.getMetadata().getFinishReason()))) {
            throw declined();
        }
        ChatResponseMetadata md = resp.getMetadata();
        return new AskResponse(answerText(generations), citations(md, docs), sources(docs), usage(md));
    }

    /**
     * The same answer as Server-Sent Events: {@code sources} before the model call, a {@code delta} per text
     * chunk, then exactly one of {@code done} or {@code error}. Only an explicit {@code Accept:
     * text/event-stream} lands here; the JSON mapping has no produces condition, so {@code *}{@code /*} and
     * a missing Accept match it exactly and stay on JSON. Failures before the model call keep their status.
     */
    @PostMapping(path = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> askStream(@RequestBody AskRequest req,
            @RequestHeader(value = "X-Turnstile-Token", required = false) String turnstileToken) {
        List<Document> docs = retrieve(req, turnstileToken);
        StringBuilder answer = new StringBuilder();
        Flux<ServerSentEvent<Object>> model = prompt(req, docs).stream().chatResponse()
                .<ServerSentEvent<Object>>handle((resp, sink) -> streamed(resp, docs, answer, sink))
                .onErrorResume(e -> Mono.just(event("error", streamError(e))));
        return Flux.concat(Mono.just(event("sources", sources(docs))), model);
    }

    private List<Document> retrieve(AskRequest req, String turnstileToken) {
        if (req.q() == null || req.q().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q is required");
        }
        if (req.q().length() > RowSearch.MAX_QUESTION_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "q must be at most " + RowSearch.MAX_QUESTION_LENGTH + " characters");
        }
        turnstile.check(turnstileToken);
        // /search with topK 5 returns exactly these rows; the eval harness relies on that.
        return rowSearch.balanced(req.q(), 5, null, false);
    }

    private ChatClient.ChatClientRequestSpec prompt(AskRequest req, List<Document> docs) {
        return chatClient.prompt()
                .options(AnthropicChatOptions.builder()
                        .effort(effort)
                        .citationDocuments(docs.stream().map(AskController::citationDocument).toList()))
                .user(req.q());
    }

    /**
     * Text chunks become deltas; the final response, the only one with a finish reason, carries the
     * citations and usage. A refusal is known only there, after its text may have streamed, so it ends in
     * {@code error} and never {@code done}; the page withdraws what it showed.
     */
    private static void streamed(ChatResponse resp, List<Document> docs, StringBuilder answer,
            SynchronousSink<ServerSentEvent<Object>> sink) {
        String finish = resp.getResults().stream()
                .map(g -> g.getMetadata().getFinishReason())
                .filter(f -> f != null && !f.isEmpty())
                .findFirst().orElse(null);
        if (finish == null) {
            String text = answerText(resp.getResults());
            if (!text.isEmpty()) {
                answer.append(text);
                sink.next(event("delta", new Delta(text)));
            }
            return;
        }
        if ("refusal".equals(finish) || answer.isEmpty()) {
            sink.error(declined());
            return;
        }
        ChatResponseMetadata md = resp.getMetadata();
        sink.next(event("done", new Done(answer.toString(), citations(md, docs), usage(md))));
    }

    /** ApiErrorHandler's split: a transient failure the SDK already retried is 503, any other 502. */
    private static StreamError streamError(Throwable e) {
        if (e instanceof RateLimitException || e instanceof InternalServerException
                || e instanceof AnthropicIoException) {
            log.warn("LLM stream failed after retry", e);
            return new StreamError(503, "llm temporarily unavailable");
        }
        if (e instanceof ResponseStatusException) {
            return new StreamError(502, "llm declined the request");
        }
        log.warn("LLM stream failed", e);
        return new StreamError(502, "llm upstream error");
    }

    private static ResponseStatusException declined() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "llm declined the request");
    }

    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.builder(data).event(name).build();
    }

    private static List<Source> sources(List<Document> docs) {
        return docs.stream().map(AskController::source).toList();
    }

    private static Usage usage(ChatResponseMetadata md) {
        return new Usage(md.getModel(), md.getUsage().getPromptTokens(), md.getUsage().getCompletionTokens());
    }

    static AnthropicCitationDocument citationDocument(Document d) {
        Map<String, Object> md = d.getMetadata();
        Object name = md.containsKey("repo") ? md.get("repo") : md.get("title");
        AnthropicCitationDocument.Builder doc = AnthropicCitationDocument.builder().customContent(sentences(d.getText()));
        // In `context`, which the model reads but never cites: in the text the chart run would surface as a
        // cited passage, and without the category a question naming one reads as if the radar had none.
        if (md.get("weeks_on_chart") instanceof Number weeks) {
            String category = md.get("category") instanceof String c ? "Category: " + c + ". " : "";
            doc.context(category + "Charted in " + weeks + " week(s): first " + md.get("first_week")
                    + ", last " + md.get("last_week") + ".");
        }
        return doc
                .title(name + " " + md.get("week"))
                .citationsEnabled(true)
                .build();
    }

    // The API splits plain text into sentences but not on a full-width "。": without one block per sentence,
    // every citation of a Chinese row returns the whole row.
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？!?])|\\n+");

    static String[] sentences(String text) {
        return SENTENCE_END.splitAsStream(text).map(String::strip).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    /** Joins the text blocks; Spring AI makes each thinking block a Generation keyed "signature" (redacted: "data"). */
    private static String answerText(List<Generation> generations) {
        return generations.stream()
                .map(Generation::getOutput)
                .filter(m -> !m.getMetadata().containsKey("signature") && !m.getMetadata().containsKey("data"))
                .map(AssistantMessage::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
    }

    /** One entry per cited row, in order of first citation; documentIndex is the row's position in docs. */
    private static List<Citation> citations(ChatResponseMetadata md, List<Document> docs) {
        List<org.springframework.ai.anthropic.Citation> cited = md.getOrDefault("citations", List.of());
        // The model can cite one passage several times; the page would show each copy.
        Map<Integer, LinkedHashSet<String>> textByDoc = new LinkedHashMap<>();
        for (org.springframework.ai.anthropic.Citation c : cited) {
            textByDoc.computeIfAbsent(c.getDocumentIndex(), i -> new LinkedHashSet<>()).add(c.getCitedText());
        }
        return textByDoc.entrySet().stream()
                .map(e -> {
                    Source s = source(docs.get(e.getKey()));
                    return new Citation(s.id(), s.source(), s.repo(), s.title(), s.url(), s.week(), s.score(),
                            e.getValue().stream().toList());
                })
                .toList();
    }

    static Source source(Document d) {
        Map<String, Object> md = d.getMetadata();
        return new Source(d.getId(), str(md.get("source")), str(md.get("repo")), str(md.get("title")),
                str(md.get("url")), str(md.get("week")), d.getScore());
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
