package com.radar.intel.embedding;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingOptionsBuilder;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.transformers.TransformersEmbeddingModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class E5PrefixEmbeddingModelTest {

    private final TransformersEmbeddingModel delegate = mock(TransformersEmbeddingModel.class);
    private final E5PrefixEmbeddingModel model = new E5PrefixEmbeddingModel(delegate);

    @Test
    void aSearchQueryIsEmbeddedAsAQuery() {
        when(delegate.embed("query: 會議摘要")).thenReturn(new float[] {1f});

        assertThat(model.embed("會議摘要")).containsExactly(1f);
    }

    @Test
    void storedRowsAreEmbeddedAsPassagesWithoutTouchingTheStoredText() {
        Document row = Document.builder().id("00000000-0000-0000-0000-000000000001").text("a/one\n\ndesc").build();
        EmbeddingOptions options = EmbeddingOptionsBuilder.builder().build();
        BatchingStrategy batching = new TokenCountBatchingStrategy();
        when(delegate.embed(anyList(), any(), any())).thenReturn(List.of(new float[] {2f}));

        assertThat(model.embed(List.of(row), options, batching)).containsExactly(new float[] {2f});

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> sent = ArgumentCaptor.forClass(List.class);
        verify(delegate).embed(sent.capture(), any(), any());
        assertThat(sent.getValue()).extracting(Document::getText).containsExactly("passage: a/one\n\ndesc");
        assertThat(row.getText()).isEqualTo("a/one\n\ndesc");
    }
}
