package com.radar.intel.ask;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Checks a Cloudflare Turnstile token with siteverify before /ask spends on the model. Without
 * {@code radar.turnstile.secret} it checks nothing, so local runs and the eval harness ask freely; the
 * prod profile requires the secret.
 */
@Component
public class TurnstileVerifier {

    private static final Logger log = LoggerFactory.getLogger(TurnstileVerifier.class);

    /** Siteverify rejects anything longer. */
    private static final int MAX_TOKEN = 2048;

    private final String secret;
    private final String verifyUrl;
    private final RestClient http;

    public TurnstileVerifier(@Value("${radar.turnstile.secret:#{null}}") String secret,
            @Value("${radar.turnstile.verify-url:https://challenges.cloudflare.com/turnstile/v0/siteverify}")
            String verifyUrl) {
        this.secret = secret;
        this.verifyUrl = verifyUrl;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    record Siteverify(boolean success, @JsonProperty("error-codes") List<String> errorCodes) {
    }

    /** 403 unless Cloudflare accepts the token; 503 when siteverify cannot answer, so a bot gets nothing. */
    public void check(String token) {
        if (secret == null) {
            return;
        }
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "bot check failed");
        }
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secret);
        form.add("response", token);
        Siteverify result;
        try {
            result = http.post().uri(verifyUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(Siteverify.class);
        } catch (RestClientException e) {
            log.warn("Turnstile siteverify failed", e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "bot check unavailable");
        }
        if (result == null || !result.success()) {
            log.info("Turnstile rejected a token: {}", result == null ? "no body" : result.errorCodes());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "bot check failed");
        }
    }
}
