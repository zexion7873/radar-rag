package com.radar.intel.notion;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal Notion REST reader — the same recipe github-radar-ui's lib/notion.ts uses:
 * resolve a data-source id, then page through {@code POST /data_sources/{id}/query}.
 * No plan-limited MCP query tools involved.
 */
@Component
public class NotionClient {

    private final RestClient http;
    private final NotionProperties props;

    public NotionClient(NotionProperties props) {
        this.props = props;
        this.http = RestClient.builder()
                .baseUrl("https://api.notion.com/v1")
                .defaultHeader("Authorization", "Bearer " + props.token())
                .defaultHeader("Notion-Version", props.version())
                .build();
    }

    /** The Trending table's rows, mapped to {@link TrendingRow}. */
    public List<TrendingRow> fetchTrending() {
        String dataSourceId = resolveDataSourceId(props.trendingDataSource());
        List<TrendingRow> rows = new ArrayList<>();
        for (JsonNode page : queryAll(dataSourceId)) {
            JsonNode p = page.path("properties");
            rows.add(new TrendingRow(
                    NotionProps.text(p, "Repo"),
                    NotionProps.dateStart(p, "Week"),
                    NotionProps.number(p, "Stars/wk"),
                    NotionProps.text(p, "Language"),
                    NotionProps.select(p, "Category"),
                    NotionProps.url(p, "Link"),
                    NotionProps.text(p, "Description"),
                    NotionProps.text(p, "Comment")));
        }
        return rows;
    }

    /** A Loot archive table's rows (Claude or Copilot — same schema, two tables). */
    public List<LootRow> fetchLoot(String dataSourceUuid) {
        String dataSourceId = resolveDataSourceId(dataSourceUuid);
        List<LootRow> rows = new ArrayList<>();
        for (JsonNode page : queryAll(dataSourceId)) {
            JsonNode p = page.path("properties");
            rows.add(new LootRow(
                    NotionProps.text(p, "Repo"),
                    NotionProps.text(p, "Intro"),
                    NotionProps.text(p, "Asset"),
                    NotionProps.select(p, "Type"),
                    NotionProps.dateStart(p, "Week"),
                    NotionProps.url(p, "Link"),
                    NotionProps.text(p, "Why"),
                    NotionProps.text(p, "How"),
                    NotionProps.select(p, "Status")));
        }
        return rows;
    }

    /** The Blog archive table's rows. */
    public List<BlogRow> fetchBlog() {
        String dataSourceId = resolveDataSourceId(props.blogDataSource());
        List<BlogRow> rows = new ArrayList<>();
        for (JsonNode page : queryAll(dataSourceId)) {
            JsonNode p = page.path("properties");
            rows.add(new BlogRow(
                    NotionProps.text(p, "Title"),
                    NotionProps.url(p, "URL"),
                    NotionProps.text(p, "Source"),
                    NotionProps.select(p, "Type"),
                    NotionProps.text(p, "Author"),
                    NotionProps.dateStart(p, "Published"),
                    NotionProps.text(p, "Brief"),
                    NotionProps.text(p, "Summary"),
                    NotionProps.text(p, "Comment")));
        }
        return rows;
    }

    /**
     * The configured uuid already IS a data-source id under the 2025-09-03 API; a GET
     * confirms it. Fall back to the legacy database endpoint if it turns out to be a
     * database id (data_sources[0].id) — mirrors the routine's resolve step.
     */
    private String resolveDataSourceId(String uuid) {
        try {
            JsonNode ds = http.get().uri("/data_sources/{id}", uuid)
                    .retrieve().body(JsonNode.class);
            if (ds != null && ds.hasNonNull("id")) {
                return ds.get("id").asText();
            }
        } catch (Exception ignored) {
            // fall through to database resolution
        }
        JsonNode db = http.get().uri("/databases/{id}", uuid)
                .retrieve().body(JsonNode.class);
        return db.path("data_sources").path(0).path("id").asText(uuid);
    }

    private List<JsonNode> queryAll(String dataSourceId) {
        List<JsonNode> out = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> body = cursor == null
                    ? Map.of("page_size", 100)
                    : Map.of("page_size", 100, "start_cursor", cursor);
            JsonNode resp = http.post().uri("/data_sources/{id}/query", dataSourceId)
                    .body(body)
                    .retrieve().body(JsonNode.class);
            resp.path("results").forEach(out::add);
            cursor = resp.path("has_more").asBoolean(false)
                    ? resp.path("next_cursor").asText(null)
                    : null;
        } while (cursor != null);
        return out;
    }
}
