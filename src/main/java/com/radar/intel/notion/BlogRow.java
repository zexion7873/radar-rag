package com.radar.intel.notion;

/** One row of the Blog archive table. Mirrors github-radar-ui's BlogRow (subset). */
public record BlogRow(
        String title,
        String url,
        String source,
        String type,
        String author,
        String published,
        String brief,
        String summary,
        String comment) {
}
