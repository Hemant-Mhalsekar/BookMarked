package com.bookmarked.library;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Which e-book variants a customer already owns (rows in user_library).
 * The library itself arrives with checkout; Your Stack already uses this so an
 * owned e-book can never be bought twice.
 */
@Component
public class LibraryLookup {

    private final JdbcTemplate jdbc;

    public LibraryLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<Long> ownedVariantIds(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList(
                "select variant_id from user_library where user_id = ?", Long.class, userId));
    }
}
