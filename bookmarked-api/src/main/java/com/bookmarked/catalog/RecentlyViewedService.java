package com.bookmarked.catalog;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.catalog.dto.CatalogDtos.BookCard;
import com.bookmarked.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * "Recently viewed" for logged-in users, kept in the database so it follows them
 * across devices (guests keep theirs in the browser). Only the latest
 * {@value #KEEP} books per user are kept.
 */
@Service
public class RecentlyViewedService {

    static final int KEEP = 20;

    private final JdbcTemplate jdbc;
    private final BookRepository books;
    private final CatalogService catalog;
    private final Clock clock;

    public RecentlyViewedService(JdbcTemplate jdbc, BookRepository books, CatalogService catalog, Clock clock) {
        this.jdbc = jdbc;
        this.books = books;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional
    public void record(AuthUser caller, Long bookId) {
        if (!books.existsById(bookId)) {
            throw ApiException.notFound("BOOK_NOT_FOUND", "Book not found.");
        }
        upsert(caller.id(), bookId, clock.instant());
        trim(caller.id());
    }

    /** Adds a guest's browser history after login; existing newer views win. */
    @Transactional
    public void merge(AuthUser caller, List<Long> bookIdsNewestFirst) {
        Instant now = clock.instant();
        List<Long> ids = bookIdsNewestFirst.stream().distinct().limit(KEEP).toList();
        for (int i = 0; i < ids.size(); i++) {
            if (books.existsById(ids.get(i))) {
                upsert(caller.id(), ids.get(i), now.minusSeconds(i + 1L));
            }
        }
        trim(caller.id());
    }

    @Transactional(readOnly = true)
    public List<BookCard> list(AuthUser caller, int limit) {
        int n = Math.max(1, Math.min(limit, KEEP));
        List<Long> ids = jdbc.queryForList(
                "select book_id from recently_viewed where user_id = ? order by viewed_at desc limit ?",
                Long.class, caller.id(), n);
        return catalog.byIds(ids);
    }

    private void upsert(Long userId, Long bookId, Instant at) {
        jdbc.update("""
                insert into recently_viewed (user_id, book_id, viewed_at) values (?, ?, ?) as nv
                on duplicate key update viewed_at = greatest(recently_viewed.viewed_at, nv.viewed_at)
                """, userId, bookId, Timestamp.from(at));
    }

    private void trim(Long userId) {
        jdbc.update("""
                delete from recently_viewed
                where user_id = ? and book_id not in (
                    select book_id from (
                        select book_id from recently_viewed where user_id = ? order by viewed_at desc limit ?
                    ) as recent
                )
                """, userId, userId, KEEP);
    }
}
