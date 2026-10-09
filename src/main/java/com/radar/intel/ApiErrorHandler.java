package com.radar.intel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Maps upstream failures to gateway statuses. Notion causes go in the JSON body (Spring's opaque
 * 500 hid a Notion 401 during the P0 bring-up). LLM causes are only logged: Spring AI 1.0.0 builds
 * their message as "HTTP code - upstream body", which must not reach /ask callers.
 */
@RestControllerAdvice
class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    /** Notion returned an HTTP error status (e.g. 401). */
    @ExceptionHandler(RestClientResponseException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    Map<String, Object> upstreamStatus(RestClientResponseException e) {
        return Map.of("error",
                "upstream " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
    }

    /** Transport-level upstream failure with no HTTP status (DNS, connection refused, timeout). */
    @ExceptionHandler(RestClientException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    Map<String, Object> upstreamTransport(RestClientException e) {
        return Map.of("error", "upstream unreachable: " + e.getMessage());
    }

    /** LLM call failed with a client error (bad request, missing/invalid ANTHROPIC_API_KEY). */
    @ExceptionHandler(NonTransientAiException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    Map<String, Object> llmClientError(NonTransientAiException e) {
        log.warn("LLM call failed", e);
        return Map.of("error", "llm upstream error");
    }

    /** LLM call still failed after its retry (rate limit, overload, upstream 5xx). */
    @ExceptionHandler(TransientAiException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    Map<String, Object> llmTransient(TransientAiException e) {
        log.warn("LLM call failed after retry", e);
        return Map.of("error", "llm temporarily unavailable");
    }
}
