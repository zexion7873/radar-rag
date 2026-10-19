package com.radar.intel.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The trending repos a question names as owner/name, spelled as the archive spells them. The embedding
 * blurs a name inside a question: "「mattpocock/skills」是做什麼的？為什麼會紅？" ranks that repo 22nd among
 * trending's repos, though github-radar-ui's own example chips ask exactly that.
 */
@Component
class RepoNames {

    // Runs of the characters GitHub allows in an owner or a name, slashes included; a URL is one run.
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9._/-]+");

    private final JdbcTemplate jdbc;

    RepoNames(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Empty, without a query, when q holds no owner/name pair. */
    List<String> in(String q) {
        Set<String> pairs = pairs(q);
        if (pairs.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("""
                select distinct metadata->>'repo' as repo from vector_store
                where metadata->>'source' = 'trending' and lower(metadata->>'repo') = any(string_to_array(?, ','))
                order by repo""", String.class, String.join(",", pairs));
    }

    /** Each adjacent pair of path segments, lowercased: "github.com/a/b" gives "github.com/a" and "a/b". */
    static Set<String> pairs(String q) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PATH.matcher(q);
        while (m.find()) {
            String[] segments = m.group().split("/");
            for (int i = 0; i + 1 < segments.length; i++) {
                // A sentence's full stop is not part of the name.
                String owner = segments[i];
                String name = segments[i + 1].replaceAll("\\.+$", "");
                if (!owner.isEmpty() && !name.isEmpty()) {
                    out.add((owner + "/" + name).toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }
}
