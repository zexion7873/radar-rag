package com.radar.intel.notion;

/** One row of a Loot archive table (Claude or Copilot). Mirrors github-radar-ui's LootRow (subset). */
public record LootRow(
        String repo,
        String intro,
        String asset,
        String type,
        String week,
        String link,
        String why,
        String how,
        String status) {
}
