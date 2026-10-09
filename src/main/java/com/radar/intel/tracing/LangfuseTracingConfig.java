package com.radar.intel.tracing;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * P4: exports the Micrometer observations (HTTP server, chat client, vector store, chat model) to
 * Langfuse over OTLP. Without keys the exporter is an empty composite, so a keyless run (CI's
 * eval-retrieval, a local dev run) traces nothing outward.
 */
@Configuration
class LangfuseTracingConfig {

    private static final Logger log = LoggerFactory.getLogger(LangfuseTracingConfig.class);

    @Bean
    SpanExporter langfuseSpanExporter(LangfuseProperties props) {
        if (!props.configured()) {
            // One missing key otherwise looks exactly like a deliberately keyless run.
            if (props.partiallyConfigured()) {
                log.warn("Langfuse tracing DISABLED: only one of LANGFUSE_PUBLIC_KEY / "
                        + "LANGFUSE_SECRET_KEY is set; set both or neither");
            }
            return SpanExporter.composite();
        }
        log.info("Langfuse tracing enabled -> {}", props.tracesEndpoint());
        String basic = Base64.getEncoder().encodeToString(
                (props.publicKey() + ":" + props.secretKey()).getBytes(StandardCharsets.UTF_8));
        return OtlpHttpSpanExporter.builder()
                .setEndpoint(props.tracesEndpoint())
                .addHeader("Authorization", "Basic " + basic)
                // Without it Langfuse may hold OTLP data back for up to 10 minutes.
                .addHeader("x-langfuse-ingestion-version", "4")
                .build();
    }

    @Bean
    ChatContentObservationFilter chatContentObservationFilter(JsonMapper json) {
        return new ChatContentObservationFilter(json);
    }
}
