package com.radar.intel.notion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from radar.notion.* in application.yml. */
@ConfigurationProperties(prefix = "radar.notion")
public record NotionProperties(String token, String baseUrl, String version, String trendingDataSource) {

    public NotionProperties {
        // A set-but-empty NOTION_BASE_URL bypasses the YAML default; RestClient then builds
        // scheme-less URIs and the first /sync dies with an unmapped 500 inside the fallback.
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("radar.notion.base-url must not be blank");
        }
    }
}
