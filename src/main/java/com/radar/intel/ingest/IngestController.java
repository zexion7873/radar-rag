package com.radar.intel.ingest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class IngestController {

    private final TrendingIngestService ingest;

    public IngestController(TrendingIngestService ingest) {
        this.ingest = ingest;
    }

    /** Pull the Trending table from Notion and (re)embed it into pgvector. */
    @PostMapping("/sync")
    public Map<String, Object> sync() {
        return Map.of("ingested", ingest.sync());
    }
}
