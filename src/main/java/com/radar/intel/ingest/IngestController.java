package com.radar.intel.ingest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class IngestController {

    private final TrendingIngestService trending;
    private final LootIngestService loot;
    private final BlogIngestService blog;

    public IngestController(TrendingIngestService trending, LootIngestService loot,
                            BlogIngestService blog) {
        this.trending = trending;
        this.loot = loot;
        this.blog = blog;
    }

    /** Pull all four archive tables from Notion and (re)embed them into pgvector. */
    @PostMapping("/sync")
    public Map<String, Object> sync() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("trending", trending.sync());
        counts.putAll(loot.sync());
        counts.put(BlogIngestService.SOURCE, blog.sync());
        return Map.of("ingested", counts);
    }
}
