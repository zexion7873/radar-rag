package com.radar.intel.notion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from radar.notion.* in application.yml. */
@ConfigurationProperties(prefix = "radar.notion")
public record NotionProperties(String token, String version, String trendingDataSource) {
}
