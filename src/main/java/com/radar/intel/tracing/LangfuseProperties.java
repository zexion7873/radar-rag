package com.radar.intel.tracing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from langfuse.* in application.yml. Without both keys, tracing stays a local no-op. */
@ConfigurationProperties(prefix = "langfuse")
public record LangfuseProperties(String publicKey, String secretKey, String host, String endpoint) {

    public boolean configured() {
        return hasText(publicKey) && hasText(secretKey);
    }

    /** Exactly one key set — almost certainly a typo'd env var, never an intentional state. */
    public boolean partiallyConfigured() {
        return hasText(publicKey) != hasText(secretKey);
    }

    /** The OTLP traces endpoint: explicit override wins, else derived from the Langfuse host. */
    public String tracesEndpoint() {
        return hasText(endpoint) ? endpoint : host + "/api/public/otel/v1/traces";
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
