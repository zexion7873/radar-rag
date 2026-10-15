package com.radar.intel.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A source's Keywords, rebuilt when its rows change. Each lookup asks Postgres for a digest of the source's
 * ids and texts, so a /sync on any instance is seen on the next question without IngestService knowing
 * this class exists; at the archive's size (hundreds of rows) the digest costs about a millisecond.
 */
@Component
class KeywordIndex {

    /** score: BM25 by row id; max: the best of them; urls: the urls of the n best rows. */
    record Match(Map<String, Double> score, double max, List<Object> urls) {

        static final Match NONE = new Match(Map.of(), 0, List.of());
    }

    private record Built(String digest, Keywords keywords, Map<String, String> urlById) {
    }

    private final JdbcTemplate jdbc;
    private final Map<String, Built> bySource = new ConcurrentHashMap<>();

    KeywordIndex(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The rows of source that share a term with q; urls holds the n best rows' urls, best first. */
    Match match(String source, String q, int n) {
        Built built = built(source);
        Map<String, Double> score = built.keywords().scores(q);
        if (score.isEmpty()) {
            return Match.NONE;
        }
        List<Object> urls = score.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(n)
                .map(e -> (Object) built.urlById().get(e.getKey()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return new Match(score, score.values().stream().mapToDouble(Double::doubleValue).max().orElse(0), urls);
    }

    private Built built(String source) {
        String digest = jdbc.queryForObject("""
                select count(*) || ':' || coalesce(md5(string_agg(id::text || md5(content), ',' order by id)), '')
                from vector_store where metadata->>'source' = ?""", String.class, source);
        Built built = bySource.get(source);
        if (built == null || !built.digest().equals(digest)) {
            Map<String, String> texts = new HashMap<>();
            Map<String, String> urls = new HashMap<>();
            jdbc.queryForList("""
                    select id::text as id, content, metadata->>'url' as url from vector_store
                    where metadata->>'source' = ?""", source).forEach(r -> {
                texts.put((String) r.get("id"), (String) r.get("content"));
                if (r.get("url") != null) {
                    urls.put((String) r.get("id"), (String) r.get("url"));
                }
            });
            built = new Built(digest, new Keywords(texts), urls);
            bySource.put(source, built);
        }
        return built;
    }
}
