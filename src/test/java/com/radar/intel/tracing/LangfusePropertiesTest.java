package com.radar.intel.tracing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangfusePropertiesTest {

    @Test
    void theTracesEndpointHangsOffTheBaseUrlWithOrWithoutATrailingSlash() {
        assertThat(new LangfuseProperties("pk", "sk", "https://jp.cloud.langfuse.com/").tracesEndpoint())
                .isEqualTo("https://jp.cloud.langfuse.com/api/public/otel/v1/traces");
        assertThat(new LangfuseProperties("pk", "sk", "https://jp.cloud.langfuse.com").tracesEndpoint())
                .isEqualTo("https://jp.cloud.langfuse.com/api/public/otel/v1/traces");
    }

    @Test
    void bothKeysConfigureItAndOneKeyIsAPartialSetup() {
        assertThat(new LangfuseProperties("pk", "sk", "u").configured()).isTrue();
        assertThat(new LangfuseProperties("pk", "", "u").configured()).isFalse();
        assertThat(new LangfuseProperties("pk", "", "u").partiallyConfigured()).isTrue();
        assertThat(new LangfuseProperties("", " ", "u").partiallyConfigured()).isFalse();
        assertThat(new LangfuseProperties(null, null, "u").configured()).isFalse();
    }
}
