package com.radar.intel.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final RateLimitFilter filter = new RateLimitFilter();

    private static MockHttpServletRequest request(String path, String... xff) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setServletPath(path);
        req.setRemoteAddr("169.254.1.1");
        for (String h : xff) {
            req.addHeader("X-Forwarded-For", h);
        }
        return req;
    }

    private int status(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void theClientIsTheRightMostForwardedEntry() {
        assertThat(RateLimitFilter.clientKey(request("/ask", "6.6.6.6, 1.2.3.4"))).isEqualTo("1.2.3.4");
        assertThat(RateLimitFilter.clientKey(request("/ask", "6.6.6.6", "7.7.7.7,1.2.3.4"))).isEqualTo("1.2.3.4");
    }

    @Test
    void withoutForwardingTheClientIsTheSocketPeer() {
        assertThat(RateLimitFilter.clientKey(request("/ask"))).isEqualTo("169.254.1.1");
    }

    @Test
    void ipv6ClientsShareTheirSlash64() {
        assertThat(RateLimitFilter.clientKey(request("/ask", "2001:db8:1:2::1")))
                .isEqualTo(RateLimitFilter.clientKey(request("/ask", "2001:db8:1:2:ffff::9")))
                .isEqualTo("20010db800010002/64");
        assertThat(RateLimitFilter.clientKey(request("/ask", "2001:db8:1:3::1"))).isEqualTo("20010db800010003/64");
    }

    @Test
    void anIpv4MappedAddressIsItsIpv4Address() {
        assertThat(RateLimitFilter.clientKey(request("/ask", "::ffff:1.2.3.4"))).isEqualTo("1.2.3.4");
    }

    @Test
    void textThatIsNotAnAddressIsKeyedAsIs() {
        assertThat(RateLimitFilter.clientKey(request("/ask", "localhost:8080"))).isEqualTo("localhost:8080");
    }

    @Test
    void theSixthAskInAMinuteIsRefusedWithRetryAfter() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(status(request("/ask", "1.2.3.4"))).isEqualTo(200);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(request("/ask", "1.2.3.4"), res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(res.getHeader("Retry-After"))).isBetween(1, 13);
        assertThat(res.getContentAsString()).isEqualTo("{\"error\":\"rate limit exceeded\"}");
    }

    @Test
    void clientsAndEndpointsHaveTheirOwnBuckets() throws Exception {
        for (int i = 0; i < 5; i++) {
            status(request("/ask", "1.2.3.4"));
        }
        assertThat(status(request("/ask", "1.2.3.4"))).isEqualTo(429);
        assertThat(status(request("/ask", "5.6.7.8"))).isEqualTo(200);
        assertThat(status(request("/search", "1.2.3.4"))).isEqualTo(200);
    }

    @Test
    void theThirtyFirstSearchInAMinuteIsRefused() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(status(request("/search", "1.2.3.4"))).isEqualTo(200);
        }
        assertThat(status(request("/search", "1.2.3.4"))).isEqualTo(429);
    }

    @Test
    void preflightsAndTheConfigFetchSpendNothing() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest preflight = request("/ask", "1.2.3.4");
            preflight.setMethod("OPTIONS");
            assertThat(status(preflight)).isEqualTo(200);
            MockHttpServletRequest config = request("/config", "1.2.3.4");
            config.setMethod("GET");
            assertThat(status(config)).isEqualTo(200);
        }
        for (int i = 0; i < 5; i++) {
            assertThat(status(request("/ask", "1.2.3.4"))).isEqualTo(200);
        }
        assertThat(status(request("/ask", "1.2.3.4"))).isEqualTo(429);
    }

    @Test
    void syncIsNotLimitedHere() throws Exception {
        for (int i = 0; i < 40; i++) {
            assertThat(status(request("/sync", "1.2.3.4"))).isEqualTo(200);
        }
    }
}
