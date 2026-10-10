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

import java.util.Map;

/**
 * Maps LLM failures to gateway statuses with a generic body. The anthropic-java SDK's exception messages
 * carry the upstream body, which must not reach a public caller, so they are only logged. Notion failures
 * never get here: IngestService reports them per source.
 */
@RestControllerAdvice
class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

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
