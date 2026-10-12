package com.radar.intel.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The week values of a source's last 28 days, counted back from its newest row, not from today: the
 * same question then retrieves the same rows on any date. Trending charts weekly, so that is its last
 * four chart weeks; a blog row's week is its publication day.
 */
@Component
class RecentWeeks {

    private final JdbcTemplate jdbc;

    RecentWeeks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<String> of(String source) {
        return jdbc.queryForList("""
                select distinct metadata->>'week' as week from vector_store
                where metadata->>'source' = ? and (metadata->>'week')::date >= (
                    select max((metadata->>'week')::date) - 27 from vector_store where metadata->>'source' = ?)
                order by week""", String.class, source, source);
    }
}
