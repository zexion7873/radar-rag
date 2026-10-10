package com.radar.intel.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
public class IngestController {

    private final TrendingIngestService ingest;
    private final String secret;

    /**
     * Without {@code radar.sync.secret} /sync is open, for a loopback dev run; set but blank, every
     * call is refused, so a prod deploy with an empty secret fails closed.
     */
    public IngestController(TrendingIngestService ingest,
            @Value("${radar.sync.secret:#{null}}") String secret) {
        this.ingest = ingest;
        this.secret = secret;
    }

    /** Pull the Trending table from Notion and (re)embed it into pgvector. */
    @PostMapping("/sync")
    public Map<String, Object> sync(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (secret != null && (secret.isBlank() || !matches(authorization))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return Map.of("ingested", ingest.sync());
    }

    // Constant-time: a byte-by-byte early exit would leak the secret's prefix through response timing.
    private boolean matches(String authorization) {
        byte[] expected = ("Bearer " + secret).getBytes(StandardCharsets.UTF_8);
        byte[] given = authorization == null ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, given);
    }
}
