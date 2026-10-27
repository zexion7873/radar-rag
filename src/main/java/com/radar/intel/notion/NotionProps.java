package com.radar.intel.notion;

import tools.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * Typed extractors for Notion property nodes. Each property carries its own "type"
 * discriminator, so we read against that rather than assuming a shape.
 */
final class NotionProps {

    private NotionProps() {
    }

    /** title and rich_text both store their runs as an array under their type key. */
    static String text(JsonNode props, String name) {
        JsonNode p = props.path(name);
        JsonNode runs = p.path(p.path("type").asString());
        if (!runs.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode run : runs) {
            sb.append(run.path("plain_text").asString(""));
        }
        return sb.toString();
    }

    static String select(JsonNode props, String name) {
        JsonNode sel = props.path(name).path("select");
        return sel.hasNonNull("name") ? sel.get("name").asString() : null;
    }

    static Double number(JsonNode props, String name) {
        JsonNode n = props.path(name).path("number");
        return n.isNumber() ? n.asDouble() : null;
    }

    static String dateStart(JsonNode props, String name) {
        JsonNode d = props.path(name).path("date");
        return d.hasNonNull("start") ? d.get("start").asString() : null;
    }

    /**
     * http(s) only: the url becomes a link on github-radar-ui's page and a value in /search's url
     * filter, which parses an ISO-8601-shaped string as a date and throws.
     */
    static String url(JsonNode props, String name) {
        JsonNode u = props.path(name).path("url");
        if (!u.isString()) {
            return null;
        }
        String url = u.asString();
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://") ? url : null;
    }
}
