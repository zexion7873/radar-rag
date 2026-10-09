package com.radar.intel.embedding;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * multilingual-e5 was trained with "query: " and "passage: " markers; without them its query and
 * passage vectors drift apart. PgVectorStore reaches the model through exactly two calls on 1.0.0:
 * {@link #embed(String)} for a search query and {@link #embed(List, EmbeddingOptions, BatchingStrategy)}
 * for the rows it stores, so those are the ones that add a marker.
 */
@Primary
@Component
@Profile("e5")
class E5PrefixEmbeddingModel implements EmbeddingModel {

    private final TransformersEmbeddingModel delegate;

    E5PrefixEmbeddingModel(TransformersEmbeddingModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public float[] embed(String text) {
        return delegate.embed("query: " + text);
    }

    @Override
    public List<float[]> embed(List<Document> documents, EmbeddingOptions options, BatchingStrategy batchingStrategy) {
        List<Document> passages = documents.stream()
                .map(d -> d.mutate().text("passage: " + d.getText()).build())
                .toList();
        return delegate.embed(passages, options, batchingStrategy);
    }

    @Override
    public float[] embed(Document document) {
        return delegate.embed("passage: " + document.getText());
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return delegate.call(request);
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }
}
