package com.radar.intel.tracing;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * P4: exports Micrometer/OTel spans (HTTP server + Spring AI chat and vector-store
 * observations) to Langfuse over OTLP with Basic auth derived from the project keys.
 * Without keys the exporter is an empty composite (no-op), so the app boots and runs
 * traced-but-unexported — the same keyless stance as /ask.
 */
@Configuration
class LangfuseTracingConfig {

    private static final Logger log = LoggerFactory.getLogger(LangfuseTracingConfig.class);

    @Bean
    SpanExporter langfuseSpanExporter(LangfuseProperties props) {
        if (!props.configured()) {
            // The half-configured state is silent otherwise: fully-wrong keys at least
            // surface as 401s from the exporter, but one missing key means no exporter
            // at all — indistinguishable from intentionally-keyless without this WARN.
            if (props.partiallyConfigured()) {
                log.warn("Langfuse tracing DISABLED: only one of LANGFUSE_PUBLIC_KEY / "
                        + "LANGFUSE_SECRET_KEY is set — set both or unset both");
            }
            return SpanExporter.composite();
        }
        log.info("Langfuse tracing enabled -> {}", props.tracesEndpoint());
        String basic = Base64.getEncoder().encodeToString(
                (props.publicKey() + ":" + props.secretKey()).getBytes(StandardCharsets.UTF_8));
        return OtlpHttpSpanExporter.builder()
                .setEndpoint(props.tracesEndpoint())
                .addHeader("Authorization", "Basic " + basic)
                .build();
    }
}
