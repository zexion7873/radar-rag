package com.radar.intel;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Surface upstream (Notion) failure causes in the JSON body. This is an internal
 * service, so exposing the raw upstream error is deliberate — Spring's default
 * opaque 500 hid a Notion 401 during the P0 bring-up.
 */
@RestControllerAdvice
class ApiErrorHandler {

    /** Upstream returned an HTTP error status (e.g. Notion 401). */
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
}
