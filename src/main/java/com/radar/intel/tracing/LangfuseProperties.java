package com.radar.intel.tracing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from radar.langfuse.* in application.yml. Without both keys, nothing is exported. */
@ConfigurationProperties(prefix = "radar.langfuse")
public record LangfuseProperties(String publicKey, String secretKey, String baseUrl) {

    public boolean configured() {
        return hasText(publicKey) && hasText(secretKey);
    }

    /** Exactly one key set: a mistyped env var, never an intended state. */
    public boolean partiallyConfigured() {
        return hasText(publicKey) != hasText(secretKey);
    }

    public String tracesEndpoint() {
        return baseUrl.replaceAll("/+$", "") + "/api/public/otel/v1/traces";
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
