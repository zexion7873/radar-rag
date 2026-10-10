package com.radar.intel.ask;

import com.anthropic.models.messages.OutputConfig;
import com.radar.intel.search.RowSearch;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * P2: grounded Q&A over the radar archive. Retrieve relevant rows from pgvector, send them to Claude
 * as citation documents, and return the rows Claude cites along with the text it cited.
 */
@RestController
public class AskController {

    private static final String SYSTEM = """
            You are the analyst for a GitHub "radar" that tracks trending AI/agent repositories.
            Answer the question using only the retrieved radar context. Be concise and specific,
            naming the relevant repositories. If the context does not cover the question, say so
            plainly rather than guessing.""";

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

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest req,
            @RequestHeader(value = "X-Turnstile-Token", required = false) String turnstileToken) {
        if (req.q() == null || req.q().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q is required");
        }
        turnstile.check(turnstileToken);
        // /search with topK 5 returns exactly these rows; the eval harness relies on that.
        List<Document> docs = rowSearch.balanced(req.q(), 5, null);
        ChatResponse resp = chatClient.prompt()
                .options(AnthropicChatOptions.builder()
                        .effort(effort)
                        .citationDocuments(docs.stream().map(AskController::citationDocument).toList()))
                .user(req.q())
                .call()
                .chatResponse();

        List<Generation> generations = resp.getResults();
        // A refusal comes back as HTTP 200 with no text; without this it reads as an empty answer.
        // Spring AI 2.0.1 turns a response with empty content into no Generation at all, so a refusal
        // that stops before any text has no finish reason to read.
        if (generations.isEmpty()
                || generations.stream().anyMatch(g -> "refusal".equals(g.getMetadata().getFinishReason()))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "llm declined the request");
        }
        ChatResponseMetadata md = resp.getMetadata();
        Usage usage = new Usage(md.getModel(), md.getUsage().getPromptTokens(), md.getUsage().getCompletionTokens());
        return new AskResponse(answerText(generations), citations(md, docs),
                docs.stream().map(AskController::source).toList(), usage);
    }

    static AnthropicCitationDocument citationDocument(Document d) {
        Map<String, Object> md = d.getMetadata();
        Object name = md.containsKey("repo") ? md.get("repo") : md.get("title");
        String text = d.getText();
        // Search keeps one week per repo, so the run it belongs to rides along for the model only;
        // the embedded text stays the row's own.
        if (md.get("weeks_on_chart") instanceof Number weeks) {
            text += "\n\nCharted in " + weeks + " week(s): first " + md.get("first_week") + ", last "
                    + md.get("last_week") + ".";
        }
        return AnthropicCitationDocument.builder()
                .plainText(text)
                .title(name + " " + md.get("week"))
                .citationsEnabled(true)
                .build();
    }

    /**
     * Joins the text blocks. Spring AI turns each thinking block into its own Generation, marked by a
     * "signature" (or, redacted, "data") metadata key; Opus 5.5 always thinks, so the first Generation
     * is usually a thinking block with empty text.
     */
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
