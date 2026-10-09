package com.radar.intel.notion;

/** One row of the Trending Archive table. Mirrors github-radar-ui's TrendingRow (subset). */
public record TrendingRow(
        String pageId,
        String repo,
        String week,
        Double starsPerWeek,
        String language,
        String category,
        String link,
        String description,
        String comment) {
}
