package com.radar.intel.ask;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * P2: grounded Q&A over the radar archive. Retrieve relevant rows from pgvector,
 * let Claude answer from them, and return the source rows as citations.
 */
@RestController
public class AskController {

    private static final String SYSTEM = """
            You are the analyst for a GitHub "radar" that tracks trending AI/agent repositories.
            Answer the question using only the retrieved radar context. Be concise and specific,
            naming the relevant repositories. If the context does not cover the question, say so
            plainly rather than guessing.""";

    private final ChatClient chatClient;
    private final Advisor ragAdvisor;

    public AskController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        this.chatClient = chatClientBuilder.defaultSystem(SYSTEM).build();
        // Embeddings are English-centric over a partly Traditional-Chinese corpus, so real matches
        // score low (~0.2-0.35); a low threshold keeps them and topK bounds the context window.
        this.ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .vectorStore(vectorStore)
                        .similarityThreshold(0.0)
                        .topK(5)
                        .build())
                .build();
    }

    public record AskRequest(String q) {
    }

    public record Citation(String id, String repo, String url, String week, Double score) {
    }

    public record AskResponse(String answer, List<Citation> citations) {
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest req) {
        if (req.q() == null || req.q().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q is required");
        }
        ChatClientResponse resp = chatClient.prompt()
                .advisors(ragAdvisor)
                .user(req.q())
                .call()
                .chatClientResponse();

        String answer = resp.chatResponse().getResult().getOutput().getText();
        return new AskResponse(answer, citations(resp));
    }

    @SuppressWarnings("unchecked")
    private static List<Citation> citations(ChatClientResponse resp) {
        Object docs = resp.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        if (!(docs instanceof List<?> list)) {
            return List.of();
        }
        return ((List<Document>) list).stream()
                .map(d -> {
                    Map<String, Object> md = d.getMetadata();
                    return new Citation(d.getId(), str(md.get("repo")), str(md.get("url")),
                            str(md.get("week")), d.getScore());
                })
                .toList();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
