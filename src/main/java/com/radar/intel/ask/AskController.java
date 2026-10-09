package com.radar.intel.ask;

import com.anthropic.models.messages.OutputConfig;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicCitationDocument;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private final VectorStoreDocumentRetriever retriever;

    public AskController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        this.chatClient = chatClientBuilder.defaultSystem(SYSTEM).build();
        // Threshold 0 keeps every candidate; topK alone bounds the context window, and /search with
        // topK 5 stays exactly this retrieval (the eval harness relies on that).
        this.retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(0.0)
                .topK(5)
                .build();
    }

    public record AskRequest(String q) {
    }

    /** A row the answer was grounded on. */
    public record Source(String id, String repo, String url, String week, Double score) {
    }

    /** A retrieved row the answer cites, with every passage cited from it. */
    public record Citation(String id, String repo, String url, String week, Double score,
            List<String> citedText) {
    }

    /** Token counts of the model call; output tokens include thinking. */
    public record Usage(String model, Integer inputTokens, Integer outputTokens) {
    }

    public record AskResponse(String answer, List<Citation> citations, List<Source> sources, Usage usage) {
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest req) {
        if (req.q() == null || req.q().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q is required");
        }
        List<Document> docs = retriever.retrieve(new Query(req.q()));
        ChatResponse resp = chatClient.prompt()
                .options(AnthropicChatOptions.builder()
                        // Opus 5.5 defaults to medium already; pinned so a model default change cannot
                        // move cost or quality.
                        .effort(OutputConfig.Effort.MEDIUM)
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

    private static AnthropicCitationDocument citationDocument(Document d) {
        Map<String, Object> md = d.getMetadata();
        return AnthropicCitationDocument.builder()
                .plainText(d.getText())
                .title(md.get("repo") + " " + md.get("week"))
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
        Map<Integer, List<String>> textByDoc = new LinkedHashMap<>();
        for (org.springframework.ai.anthropic.Citation c : cited) {
            textByDoc.computeIfAbsent(c.getDocumentIndex(), i -> new ArrayList<>()).add(c.getCitedText());
        }
        return textByDoc.entrySet().stream()
                .map(e -> {
                    Source s = source(docs.get(e.getKey()));
                    return new Citation(s.id(), s.repo(), s.url(), s.week(), s.score(), e.getValue());
                })
                .toList();
    }

    private static Source source(Document d) {
        Map<String, Object> md = d.getMetadata();
        return new Source(d.getId(), str(md.get("repo")), str(md.get("url")), str(md.get("week")), d.getScore());
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
