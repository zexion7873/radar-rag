package com.radar.intel.embedding;

import ai.onnxruntime.OrtException;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Map;

@Configuration
class EmbeddingConfig {

    // Pad to the batch's longest text; truncate at the model's max_seq_length (1 of the 190 fixture
    // rows is longer).
    private static final Map<String, String> TOKENIZER_OPTIONS =
            Map.of("padding", "true", "truncation", "true", "maxLength", "128");

    @Bean
    OnnxEmbeddingModel embeddingModel(EmbeddingProperties props, ObjectProvider<ObservationRegistry> registry)
            throws IOException, OrtException {
        return new OnnxEmbeddingModel(props.model(), props.tokenizer(), TOKENIZER_OPTIONS,
                registry.getIfUnique(() -> ObservationRegistry.NOOP));
    }
}
