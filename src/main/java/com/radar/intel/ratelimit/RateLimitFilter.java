package com.radar.intel.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Per-client token buckets on the public endpoints. Prod only: the eval harness sends every golden
 * query from one address.
 */
@Component
@Profile("prod")
class RateLimitFilter extends OncePerRequestFilter {

    // An evicted client starts again with full buckets, which a fresh address gets anyway.
    private static final int MAX_CLIENTS = 10_000;

    private final Map<String, Bucket> buckets = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                    return size() > MAX_CLIENTS;
                }
            });

    /** /ask spends Anthropic credit per call; /search only CPU. /sync has its own secret. */
    private static Bucket newBucket(String path) {
        return switch (path) {
            case "/ask" -> Bucket.builder()
                    .addLimit(l -> l.capacity(5).refillGreedy(5, Duration.ofMinutes(1)))
                    .addLimit(l -> l.capacity(20).refillGreedy(20, Duration.ofDays(1)))
                    .build();
            case "/search" -> Bucket.builder()
                    .addLimit(l -> l.capacity(30).refillGreedy(30, Duration.ofMinutes(1)))
                    .build();
            default -> throw new IllegalArgumentException(path);
        };
    }

    // The servlet path is decoded, normalized and stripped of ;parameters, as MVC maps it; the raw URI
    // would let "/ask;x" through unlimited.
    // A browser sends an OPTIONS preflight before each cross-origin POST /ask; counting it would halve
    // the limit for github-radar-ui's page.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getServletPath();
        return "OPTIONS".equals(req.getMethod()) || !path.equals("/ask") && !path.equals("/search");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getServletPath();
        ConsumptionProbe probe = buckets.computeIfAbsent(path + " " + clientKey(req), k -> newBucket(path))
                .tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(req, res);
            return;
        }
        res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        res.setHeader(HttpHeaders.RETRY_AFTER,
                String.valueOf(TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1));
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write("{\"error\":\"rate limit exceeded\"}");
    }

    /**
     * The right-most X-Forwarded-For entry: Cloud Run's front end appends the address it saw, and every
     * entry left of it is the caller's own text. IPv6 clients are keyed by their /64, which one
     * subscriber usually holds whole.
     */
    static String clientKey(HttpServletRequest req) {
        List<String> xff = Collections.list(req.getHeaders("X-Forwarded-For"));
        String ip = xff.isEmpty() ? req.getRemoteAddr() : lastEntry(xff.getLast());
        if (ip.indexOf(':') < 0) {
            return ip;
        }
        InetAddress addr;
        try {
            addr = InetAddress.ofLiteral(ip);   // a literal only: never a DNS lookup on header text
        } catch (IllegalArgumentException e) {
            return ip;
        }
        byte[] bytes = addr.getAddress();
        return bytes.length == 16 ? HexFormat.of().formatHex(bytes, 0, 8) + "/64" : addr.getHostAddress();
    }

    private static String lastEntry(String header) {
        return header.substring(header.lastIndexOf(',') + 1).strip();
    }
}
