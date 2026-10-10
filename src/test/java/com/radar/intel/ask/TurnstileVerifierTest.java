package com.radar.intel.ask;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WireMockTest
class TurnstileVerifierTest {

    private static TurnstileVerifier verifier(WireMockRuntimeInfo wm, String secret) {
        return new TurnstileVerifier(secret, wm.getHttpBaseUrl() + "/siteverify");
    }

    @Test
    void withoutASecretNothingIsChecked(WireMockRuntimeInfo wm) {
        assertThatCode(() -> verifier(wm, null).check(null)).doesNotThrowAnyException();
        verify(0, postRequestedFor(urlEqualTo("/siteverify")));
    }

    @Test
    void anAcceptedTokenPasses(WireMockRuntimeInfo wm) {
        stubFor(post("/siteverify").willReturn(okJson("{\"success\":true,\"error-codes\":[]}")));

        assertThatCode(() -> verifier(wm, "s3cret").check("tok")).doesNotThrowAnyException();
        verify(postRequestedFor(urlEqualTo("/siteverify"))
                .withRequestBody(containing("secret=s3cret"))
                .withRequestBody(containing("response=tok")));
    }

    @Test
    void aRejectedTokenIs403(WireMockRuntimeInfo wm) {
        stubFor(post("/siteverify").willReturn(
                okJson("{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}")));

        assertStatus(() -> verifier(wm, "s3cret").check("used-token"), HttpStatus.FORBIDDEN);
    }

    @Test
    void aMissingOrOversizedTokenIs403WithoutAsking(WireMockRuntimeInfo wm) {
        assertStatus(() -> verifier(wm, "s3cret").check(null), HttpStatus.FORBIDDEN);
        assertStatus(() -> verifier(wm, "s3cret").check(" "), HttpStatus.FORBIDDEN);
        assertStatus(() -> verifier(wm, "s3cret").check("x".repeat(2049)), HttpStatus.FORBIDDEN);
        verify(0, postRequestedFor(urlEqualTo("/siteverify")));
    }

    @Test
    void anUnreachableSiteverifyIs503NotAPass(WireMockRuntimeInfo wm) {
        stubFor(post("/siteverify").willReturn(aResponse().withStatus(500)));

        assertStatus(() -> verifier(wm, "s3cret").check("tok"), HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }
}
