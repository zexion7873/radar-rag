package com.radar.intel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Maps upstream failures to gateway statuses with a generic body. Upstream text is only logged: Notion's
 * error body and the anthropic-java SDK's exception messages must not reach a public caller. Notion's
 * status code stays in the body, since an opaque 502 once hid a Notion 401.
 */
@RestControllerAdvice
class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    /** Notion returned an HTTP error status (e.g. 401). */
    @ExceptionHandler(RestClientResponseException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    Map<String, Object> upstreamStatus(RestClientResponseException e) {
        log.warn("Notion call failed: {} {}", e.getStatusCode().value(), e.getResponseBodyAsString());
        return Map.of("error", "upstream " + e.getStatusCode().value());
    }

    /** Transport-level upstream failure with no HTTP status (DNS, connection refused, timeout). */
    @ExceptionHandler(RestClientException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    Map<String, Object> upstreamTransport(RestClientException e) {
        log.warn("Notion unreachable", e);
        return Map.of("error", "upstream unreachable");
    }

    /** LLM call failed with a client error (bad request, missing/invalid ANTHROPIC_API_KEY). */
    @ExceptionHandler(AnthropicServiceException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    Map<String, Object> llmClientError(AnthropicServiceException e) {
        log.warn("LLM call failed", e);
        return Map.of("error", "llm upstream error");
    }

    /** LLM call still failed after the SDK's retry (rate limit, overload, upstream 5xx, network). */
    @ExceptionHandler({RateLimitException.class, InternalServerException.class, AnthropicIoException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    Map<String, Object> llmTransient(RuntimeException e) {
        log.warn("LLM call failed after retry", e);
        return Map.of("error", "llm temporarily unavailable");
    }
}
