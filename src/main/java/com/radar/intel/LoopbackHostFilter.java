package com.radar.intel;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Outside prod, answers only requests addressed to a loopback name. The loopback bind keeps the LAN
 * out but not a DNS-rebinding page, whose own hostname resolves to 127.0.0.1 and whose requests are
 * same-origin to it, so it could spend the developer's Anthropic key through /ask and read the answer.
 */
@Component
@Profile("!prod")
class LoopbackHostFilter extends OncePerRequestFilter {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        // The raw header, not getServerName(): a forwarded-header strategy would let the page's own
        // X-Forwarded-Host stand in for it.
        String host = req.getHeader(HttpHeaders.HOST);
        if (LOOPBACK.contains(hostName(host == null ? req.getServerName() : host))) {
            chain.doFilter(req, res);
            return;
        }
        res.sendError(HttpServletResponse.SC_FORBIDDEN, "host not allowed");
    }

    private static String hostName(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        int end = h.startsWith("[") ? h.indexOf(']') + 1 : h.indexOf(':');
        return end > 0 ? h.substring(0, end) : h;
    }
}
