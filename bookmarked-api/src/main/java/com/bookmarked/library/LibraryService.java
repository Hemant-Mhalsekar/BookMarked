package com.bookmarked.library;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.CustomerOnly;
import com.bookmarked.config.AppProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * My Library: the e-books a customer owns (rows in user_library), their reading
 * progress, and short-lived links to read or download them. Every query is scoped
 * to the caller's user id, so nobody can reach someone else's books.
 */
@Service
public class LibraryService {

    public record LibraryBook(Long variantId, Long bookId, String slug, String title, List<String> authors,
                              String coverImageUrl, Integer pages, Instant purchasedAt, Integer lastPage,
                              Integer totalPages, Instant lastReadAt, int downloadsLeft, boolean available) {
    }

    public record ProgressRequest(@NotNull @Min(1) @Max(100000) Integer page,
                                  @NotNull @Min(1) @Max(100000) Integer totalPages) {
    }

    public record Progress(int lastPage, int totalPages, Instant updatedAt) {
    }

    public record FileLink(String url, Instant expiresAt, Integer downloadsLeft) {
    }

    private static final String SELECT = """
            select ul.variant_id, b.id, b.slug, b.title, b.cover_image_url, coalesce(ef.page_count, bv.pages),
                   ul.purchased_at, rp.last_page, rp.total_pages, rp.updated_at, ul.download_count,
                   ef.storage_key is not null
            from user_library ul
            join book_variants bv on bv.id = ul.variant_id
            join books b on b.id = bv.book_id
            left join reading_progress rp on rp.user_id = ul.user_id and rp.variant_id = ul.variant_id
            left join ebook_files ef on ef.variant_id = ul.variant_id
            where ul.user_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final FileLinks links;
    private final Clock clock;
    private final int downloadLimit;

    public LibraryService(JdbcTemplate jdbc, FileLinks links, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.links = links;
        this.clock = clock;
        this.downloadLimit = props.business().ebookDownloadLimit();
    }

    @Transactional(readOnly = true)
    public List<LibraryBook> list(AuthUser caller) {
        CustomerOnly.require(caller);
        List<Object[]> rows = jdbc.query(SELECT + " order by coalesce(rp.updated_at, ul.purchased_at) desc",
                raw(), caller.id());
        return withAuthors(rows);
    }

    @Transactional(readOnly = true)
    public LibraryBook get(AuthUser caller, long variantId) {
        CustomerOnly.require(caller);
        List<Object[]> rows = jdbc.query(SELECT + " and ul.variant_id = ?", raw(), caller.id(), variantId);
        if (rows.isEmpty()) {
            throw notOwned();
        }
        return withAuthors(rows).get(0);
    }

    @Transactional(readOnly = true)
    public List<Long> ownedIds(AuthUser caller) {
        CustomerOnly.require(caller);
        return jdbc.queryForList("select variant_id from user_library where user_id = ?", Long.class, caller.id());
    }

    /** Saves where the reader is; the page is clamped to the book. Works across devices. */
    @Transactional
    public Progress saveProgress(AuthUser caller, long variantId, ProgressRequest req) {
        requireOwned(caller, variantId);
        int total = req.totalPages();
        int page = Math.min(Math.max(1, req.page()), total);
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                insert into reading_progress (user_id, variant_id, last_page, total_pages, updated_at) values (?, ?, ?, ?, ?)
                on duplicate key update last_page = values(last_page), total_pages = values(total_pages), updated_at = values(updated_at)
                """, caller.id(), variantId, page, total, now);
        return new Progress(page, total, now.toInstant());
    }

    /** A 5-minute link the reader opens directly; reusable until it expires. */
    @Transactional(readOnly = true)
    public FileLink readLink(AuthUser caller, long variantId) {
        requireOwned(caller, variantId);
        requireFile(variantId);
        FileLinks.Link link = links.create(FileLinks.Purpose.READ, caller.id(), variantId);
        return new FileLink(link.url(), link.expiresAt(), null);
    }

    /**
     * A single-use download link. Takes one of the 5 downloads straight away (with an
     * atomic UPDATE, so parallel clicks can't go over the limit) and logs it.
     */
    @Transactional
    public FileLink downloadLink(AuthUser caller, long variantId, String ip) {
        requireOwned(caller, variantId);
        requireFile(variantId);
        int taken = jdbc.update("""
                update user_library set download_count = download_count + 1
                where user_id = ? and variant_id = ? and download_count < ?
                """, caller.id(), variantId, downloadLimit);
        if (taken == 0) {
            throw ApiException.conflict("DOWNLOAD_LIMIT",
                    "You've downloaded this book " + downloadLimit + " times, the most allowed. You can still read it here.");
        }
        FileLinks.Link link = links.create(FileLinks.Purpose.DOWNLOAD, caller.id(), variantId);
        jdbc.update("insert into download_logs (user_id, variant_id, ip_address, downloaded_at, token_id) values (?, ?, ?, ?, ?)",
                caller.id(), variantId, ip == null || ip.length() > 45 ? null : ip, Timestamp.from(clock.instant()), link.nonce());
        Integer used = jdbc.queryForObject("select download_count from user_library where user_id = ? and variant_id = ?",
                Integer.class, caller.id(), variantId);
        return new FileLink(link.url(), link.expiresAt(), downloadLimit - (used == null ? 0 : used));
    }

    // ------------------------------------------------------------------

    void requireOwned(AuthUser caller, long variantId) {
        CustomerOnly.require(caller);
        Integer n = jdbc.queryForObject("select count(*) from user_library where user_id = ? and variant_id = ?",
                Integer.class, caller.id(), variantId);
        if (n == null || n == 0) {
            throw notOwned();
        }
    }

    private void requireFile(long variantId) {
        Integer n = jdbc.queryForObject("select count(*) from ebook_files where variant_id = ?", Integer.class, variantId);
        if (n == null || n == 0) {
            throw ApiException.conflict("EBOOK_NOT_READY",
                    "This e-book's file is still being prepared. We'll have it ready shortly.");
        }
    }

    /** Same answer whether the book doesn't exist or belongs to someone else. */
    private static ApiException notOwned() {
        return ApiException.notFound("NOT_IN_LIBRARY", "That book isn't in your library.");
    }

    private static RowMapper<Object[]> raw() {
        return (rs, i) -> new Object[]{rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6), rs.getTimestamp(7).toInstant(), rs.getObject(8), rs.getObject(9),
                rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant(), rs.getInt(11), rs.getBoolean(12)};
    }

    private List<LibraryBook> withAuthors(List<Object[]> rows) {
        Map<Long, List<String>> authors = new HashMap<>();
        if (!rows.isEmpty()) {
            List<Long> bookIds = rows.stream().map(r -> (Long) r[1]).distinct().toList();
            String in = String.join(",", Collections.nCopies(bookIds.size(), "?"));
            jdbc.query("select ba.book_id, a.name from book_authors ba join authors a on a.id = ba.author_id "
                            + "where ba.book_id in (" + in + ") order by a.name",
                    rs -> {
                        authors.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
                    }, bookIds.toArray());
        }
        return rows.stream().map(r -> new LibraryBook((Long) r[0], (Long) r[1], (String) r[2], (String) r[3],
                authors.getOrDefault((Long) r[1], List.of()), (String) r[4], toInt(r[5]), (Instant) r[6],
                toInt(r[7]), toInt(r[8]), (Instant) r[9], Math.max(0, downloadLimit - (Integer) r[10]),
                (Boolean) r[11])).toList();
    }

    private static Integer toInt(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }
}
