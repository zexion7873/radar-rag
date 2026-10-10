package com.radar.intel.notion;

/** One row of the Blog Archive table. Mirrors github-radar-ui's BlogRow (subset). */
public record BlogRow(
        String pageId,
        String title,
        String url,
        String type,
        String published,
        String archived,
        String brief,
        String comment) {
}
