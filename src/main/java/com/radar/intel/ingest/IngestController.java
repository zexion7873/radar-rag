package com.radar.intel.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
public class IngestController {

    private final IngestService ingest;
    private final String secret;

    /**
     * Without {@code radar.sync.secret} /sync is open, for a loopback dev run; set but blank, every
     * call is refused, so a prod deploy with an empty secret fails closed.
     */
    public IngestController(IngestService ingest,
            @Value("${radar.sync.secret:#{null}}") String secret) {
        this.ingest = ingest;
        this.secret = secret;
    }

    /**
     * Pull every Notion table and (re)embed it into pgvector. 502 when any source failed, with the
     * sources that did sync committed, so the weekly cron turns red instead of hiding a partial sync.
     */
    @PostMapping("/sync")
    public ResponseEntity<IngestService.SyncResult> sync(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (secret != null && (secret.isBlank() || !matches(authorization))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        IngestService.SyncResult result = ingest.sync();
        return ResponseEntity.status(result.failed().isEmpty() ? HttpStatus.OK : HttpStatus.BAD_GATEWAY)
                .body(result);
    }

    // Constant-time: a byte-by-byte early exit would leak the secret's prefix through response timing.
    private boolean matches(String authorization) {
        byte[] expected = ("Bearer " + secret).getBytes(StandardCharsets.UTF_8);
        byte[] given = authorization == null ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, given);
    }
}
