package com.radar.intel.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/** Bound from radar.embedding.* in application.yml: the files scripts/fetch-models.sh writes. */
@ConfigurationProperties(prefix = "radar.embedding")
public record EmbeddingProperties(Path model, Path tokenizer) {
}
