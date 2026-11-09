package com.bookmarked.review;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.CustomerOnly;
import com.bookmarked.common.PageResponse;
import com.bookmarked.common.RateLimitService;
import com.bookmarked.notification.NotificationService;
import com.bookmarked.notification.NotificationService.Type;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Buyers-only reviews: one per customer per book (shared by all its formats).
 * <ul>
 *   <li>Eligible once something was delivered: an e-book on purchase, a printed book on delivery.</li>
 *   <li>Rating 1–5, optional title up to 100 characters, comment 10–2000 characters; HTML is stripped.</li>
 *   <li>One "Helpful" vote per customer, never on your own review.</li>
 *   <li>Three reports hide a review until a moderator restores or removes it.</li>
 *   <li>Every change recalculates the book's average and count in the same transaction.</li>
 * </ul>
 */
@Service
public class ReviewService {

    public static final int HIDE_AFTER_REPORTS = 3;
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\r]+");

    public enum Sort {
        HELPFUL("r.helpful_count desc, r.created_at desc"),
        NEWEST("r.created_at desc"),
        HIGHEST("r.rating desc, r.helpful_count desc, r.created_at desc"),
        LOWEST("r.rating asc, r.helpful_count desc, r.created_at desc");

        final String orderBy;

        Sort(String orderBy) {
            this.orderBy = orderBy;
        }
    }

    public record ReviewRequest(@NotNull @Min(1) @Max(5) Integer rating,
                                @Size(max = 100, message = "Keep the title under 100 characters") String title,
                                @NotBlank(message = "Write a few words about the book")
                                @Size(min = 10, max = 2000, message = "Write between 10 and 2000 characters") String comment) {
    }

    public record ReportRequest(@NotBlank @Size(min = 3, max = 200, message = "Tell us what's wrong (3–200 characters)") String reason) {
    }

    public record ReviewView(Long id, Long bookId, int rating, String title, String comment, String authorName,
                             boolean verifiedPurchase, int helpfulCount, Instant createdAt, boolean edited,
                             boolean mine, boolean votedHelpful, boolean reported) {
    }

    public record Summary(BigDecimal avgRating, int ratingCount, Map<Integer, Integer> distribution,
                          boolean canReview, String cannotReviewReason, ReviewView myReview) {
    }

    public record MyReview(Long id, Long bookId, String slug, String bookTitle, String coverImageUrl, int rating,
                           String title, String comment, String status, int helpfulCount, Instant createdAt,
                           Instant updatedAt) {
    }

    public record WaitingBook(Long bookId, String slug, String title, String coverImageUrl, Instant deliveredAt) {
    }

    public record Mine(List<MyReview> reviews, List<WaitingBook> waiting) {
    }

    private final JdbcTemplate jdbc;
    private final RateLimitService rateLimit;
    private final NotificationService notifications;
    private final Clock clock;

    public ReviewService(JdbcTemplate jdbc, RateLimitService rateLimit, NotificationService notifications, Clock clock) {
        this.jdbc = jdbc;
        this.rateLimit = rateLimit;
        this.notifications = notifications;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Reading (public; the caller is optional)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<ReviewView> list(String slug, Sort sort, Integer stars, AuthUser caller, int page, int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be >= 0 and size between 1 and 50");
        }
        if (stars != null && (stars < 1 || stars > 5)) {
            throw ApiException.badRequest("INVALID_RATING", "Filter by 1 to 5 stars.");
        }
        long bookId = bookId(slug);
        String where = " where r.book_id = ? and r.status = 'VISIBLE'" + (stars == null ? "" : " and r.rating = ?");
        Object[] countArgs = stars == null ? new Object[]{bookId} : new Object[]{bookId, stars};
        long total = jdbc.queryForObject("select count(*) from reviews r" + where, Long.class, countArgs);

        long viewer = caller == null ? -1 : caller.id();
        List<Object> args = new ArrayList<>(List.of(viewer, viewer, viewer, bookId));
        if (stars != null) {
            args.add(stars);
        }
        args.add(size);
        args.add((long) page * size);
        List<ReviewView> rows = jdbc.query(select() + where + " order by " + (sort == null ? Sort.HELPFUL : sort).orderBy
                + " limit ? offset ?", view(), args.toArray());
        return new PageResponse<>(rows, page, size, total, (int) ((total + size - 1) / size));
    }

    @Transactional(readOnly = true)
    public Summary summary(String slug, AuthUser caller) {
        long bookId = bookId(slug);
        Map<Integer, Integer> distribution = new LinkedHashMap<>();
        for (int s = 5; s >= 1; s--) {
            distribution.put(s, 0);
        }
        jdbc.query("select rating, count(*) from reviews where book_id = ? and status = 'VISIBLE' group by rating",
                rs -> {
                    distribution.put(rs.getInt(1), rs.getInt(2));
                }, bookId);
        BigDecimal avg = jdbc.queryForObject("select avg_rating from books where id = ?", BigDecimal.class, bookId);
        int count = distribution.values().stream().mapToInt(Integer::intValue).sum();

        boolean canReview = false;
        String reason = "Log in to review this book.";
        ReviewView mine = null;
        if (caller != null && caller.isStaff()) {
            reason = "Staff accounts can't review books.";
        } else if (caller != null) {
            List<ReviewView> own = jdbc.query(select() + " where r.book_id = ? and r.user_id = ? and r.status <> 'REMOVED'",
                    view(), caller.id(), caller.id(), caller.id(), bookId, caller.id());
            mine = own.isEmpty() ? null : own.get(0);
            if (mine != null) {
                reason = "You've reviewed this book. You can edit your review.";
            } else if (!eligible(caller.id(), bookId)) {
                reason = "Only customers who bought this book can review it (printed books once delivered).";
            } else {
                canReview = true;
                reason = null;
            }
        }
        return new Summary(avg, count, distribution, canReview, reason, mine);
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    @Transactional
    public ReviewView create(AuthUser caller, String slug, ReviewRequest req) {
        CustomerOnly.require(caller);
        rateLimit.check(RateLimitService.Rule.REVIEW, "user:" + caller.id());
        long bookId = bookId(slug);
        if (!eligible(caller.id(), bookId)) {
            throw ApiException.forbidden("NOT_ELIGIBLE",
                    "Only customers who bought this book can review it (printed books once delivered).");
        }
        Clean c = clean(req);
        Timestamp now = Timestamp.from(clock.instant());
        try {
            jdbc.update("""
                    insert into reviews (user_id, book_id, rating, title, comment, verified_purchase, status, created_at, updated_at)
                    values (?, ?, ?, ?, ?, true, 'VISIBLE', ?, ?)
                    """, caller.id(), bookId, req.rating(), c.title(), c.comment(), now, now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("ALREADY_REVIEWED", "You've already reviewed this book. Edit your review instead.");
        }
        recalculate(bookId);
        Long id = jdbc.queryForObject("select id from reviews where user_id = ? and book_id = ?", Long.class,
                caller.id(), bookId);
        return get(id, caller);
    }

    @Transactional
    public ReviewView update(AuthUser caller, long id, ReviewRequest req) {
        CustomerOnly.require(caller);
        rateLimit.check(RateLimitService.Rule.REVIEW, "user:" + caller.id());
        long bookId = ownBookId(caller, id);
        Clean c = clean(req);
        jdbc.update("update reviews set rating = ?, title = ?, comment = ?, updated_at = ? where id = ?",
                req.rating(), c.title(), c.comment(), Timestamp.from(clock.instant()), id);
        recalculate(bookId);
        return get(id, caller);
    }

    @Transactional
    public void delete(AuthUser caller, long id) {
        CustomerOnly.require(caller);
        long bookId = ownBookId(caller, id);
        jdbc.update("delete from review_votes where review_id = ?", id);
        jdbc.update("delete from review_reports where review_id = ?", id);
        jdbc.update("delete from reviews where id = ?", id);
        recalculate(bookId);
    }

    /** Adds or removes the caller's "Helpful" vote. Repeating either is harmless. */
    @Transactional
    public ReviewView helpful(AuthUser caller, long id, boolean add) {
        CustomerOnly.require(caller);
        rateLimit.check(RateLimitService.Rule.REVIEW, "user:" + caller.id());
        Long author = visibleAuthor(id);
        if (author.equals(caller.id())) {
            throw ApiException.conflict("OWN_REVIEW", "You can't vote on your own review.");
        }
        if (add) {
            jdbc.update("insert ignore into review_votes (review_id, user_id) values (?, ?)", id, caller.id());
        } else {
            jdbc.update("delete from review_votes where review_id = ? and user_id = ?", id, caller.id());
        }
        jdbc.update("update reviews set helpful_count = (select count(*) from review_votes where review_id = ?) where id = ?", id, id);
        return get(id, caller);
    }

    /** One report per customer; the third report hides the review until a moderator decides. */
    @Transactional
    public void report(AuthUser caller, long id, String reason) {
        CustomerOnly.require(caller);
        rateLimit.check(RateLimitService.Rule.REVIEW, "user:" + caller.id());
        Long author = visibleAuthor(id);
        if (author.equals(caller.id())) {
            throw ApiException.conflict("OWN_REVIEW", "You can't report your own review.");
        }
        int added = jdbc.update("insert ignore into review_reports (review_id, user_id, reason, created_at) values (?, ?, ?, ?)",
                id, caller.id(), stripHtml(reason), Timestamp.from(clock.instant()));
        if (added == 0) {
            return;   // already reported by this customer
        }
        jdbc.update("update reviews set report_count = (select count(*) from review_reports where review_id = ?) where id = ?", id, id);
        int hidden = jdbc.update("update reviews set status = 'HIDDEN', updated_at = ? where id = ? and status = 'VISIBLE' and report_count >= ?",
                Timestamp.from(clock.instant()), id, HIDE_AFTER_REPORTS);
        if (hidden == 1) {
            long bookId = jdbc.queryForObject("select book_id from reviews where id = ?", Long.class, id);
            recalculate(bookId);
            notifications.notifyStaff("REVIEW_MODERATE", Type.REVIEW_REPORTED, "A review was hidden after "
                    + HIDE_AFTER_REPORTS + " reports", "Check it and restore or remove it.", "/admin/reviews");
        }
    }

    @Transactional(readOnly = true)
    public Mine mine(AuthUser caller) {
        CustomerOnly.require(caller);
        List<MyReview> reviews = jdbc.query("""
                select r.id, b.id, b.slug, b.title, b.cover_image_url, r.rating, r.title, r.comment, r.status,
                       r.helpful_count, r.created_at, r.updated_at
                from reviews r join books b on b.id = r.book_id
                where r.user_id = ? and r.status <> 'REMOVED' order by r.created_at desc
                """, (rs, i) -> new MyReview(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getInt(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10),
                rs.getTimestamp(11).toInstant(), rs.getTimestamp(12).toInstant()), caller.id());
        List<WaitingBook> waiting = jdbc.query("""
                select b.id, b.slug, b.title, b.cover_image_url, max(oi.delivered_at)
                from order_items oi
                join orders o on o.id = oi.order_id
                join book_variants bv on bv.id = oi.variant_id
                join books b on b.id = bv.book_id
                where o.user_id = ? and oi.delivered_at is not null
                  and not exists (select 1 from reviews r where r.user_id = o.user_id and r.book_id = b.id)
                group by b.id, b.slug, b.title, b.cover_image_url
                order by max(oi.delivered_at) desc
                limit 20
                """, (rs, i) -> new WaitingBook(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getTimestamp(5).toInstant()), caller.id());
        return new Mine(reviews, waiting);
    }

    // ------------------------------------------------------------------
    // Moderation (REVIEW_MODERATE)
    // ------------------------------------------------------------------

    public record ModerationView(Long id, Long bookId, String slug, String bookTitle, int rating, String title,
                                 String comment, String authorName, String authorEmail, String status,
                                 int reportCount, List<String> reportReasons, Instant createdAt) {
    }

    @Transactional(readOnly = true)
    public PageResponse<ModerationView> moderationQueue(String filter, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be >= 0 and size between 1 and 100");
        }
        String where = switch (filter == null ? "ATTENTION" : filter.toUpperCase(Locale.ROOT)) {
            case "ATTENTION" -> " where r.status = 'HIDDEN' or (r.status = 'VISIBLE' and r.report_count > 0)";
            case "HIDDEN" -> " where r.status = 'HIDDEN'";
            case "REMOVED" -> " where r.status = 'REMOVED'";
            case "ALL" -> "";
            default -> throw ApiException.badRequest("INVALID_FILTER", "Use ATTENTION, HIDDEN, REMOVED or ALL.");
        };
        long total = jdbc.queryForObject("select count(*) from reviews r" + where, Long.class);
        List<ModerationView> rows = jdbc.query("""
                select r.id, b.id, b.slug, b.title, r.rating, r.title, r.comment, u.full_name, u.email, r.status,
                       r.report_count, r.created_at
                from reviews r join books b on b.id = r.book_id join users u on u.id = r.user_id
                """ + where + " order by r.report_count desc, r.updated_at desc limit ? offset ?",
                (rs, i) -> new ModerationView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getInt(11), new ArrayList<>(), rs.getTimestamp(12).toInstant()),
                size, (long) page * size);
        for (ModerationView v : rows) {
            v.reportReasons().addAll(jdbc.queryForList(
                    "select reason from review_reports where review_id = ? order by created_at", String.class, v.id()));
        }
        return new PageResponse<>(rows, page, size, total, (int) ((total + size - 1) / size));
    }

    /** Back on the book page; earlier reports are cleared so they don't hide it again. */
    @Transactional
    public void restore(long id) {
        long bookId = bookOf(id);
        jdbc.update("delete from review_reports where review_id = ?", id);
        jdbc.update("update reviews set status = 'VISIBLE', report_count = 0, updated_at = ? where id = ?",
                Timestamp.from(clock.instant()), id);
        recalculate(bookId);
    }

    /** Taken down for good (the author can't edit it back). */
    @Transactional
    public void remove(long id) {
        long bookId = bookOf(id);
        jdbc.update("update reviews set status = 'REMOVED', updated_at = ? where id = ?", Timestamp.from(clock.instant()), id);
        recalculate(bookId);
    }

    // ------------------------------------------------------------------

    /** Bought it and something of it was delivered: e-books on purchase, printed books on delivery. */
    boolean eligible(long userId, long bookId) {
        Integer n = jdbc.queryForObject("""
                select count(*) from order_items oi
                join orders o on o.id = oi.order_id
                join book_variants bv on bv.id = oi.variant_id
                where o.user_id = ? and bv.book_id = ? and oi.delivered_at is not null
                """, Integer.class, userId, bookId);
        return n != null && n > 0;
    }

    /** Average and count over visible reviews, stored on the book for listings and sorting. */
    private void recalculate(long bookId) {
        jdbc.update("""
                update books b set
                  avg_rating = coalesce((select round(avg(r.rating), 1) from reviews r where r.book_id = b.id and r.status = 'VISIBLE'), 0),
                  rating_count = (select count(*) from reviews r where r.book_id = b.id and r.status = 'VISIBLE')
                where b.id = ?
                """, bookId);
    }

    private long bookId(String slug) {
        List<Long> ids = jdbc.queryForList("select id from books where slug = ? and active = true", Long.class, slug);
        if (ids.isEmpty()) {
            throw ApiException.notFound("BOOK_NOT_FOUND", "Book not found.");
        }
        return ids.get(0);
    }

    private long bookOf(long reviewId) {
        List<Long> ids = jdbc.queryForList("select book_id from reviews where id = ?", Long.class, reviewId);
        if (ids.isEmpty()) {
            throw ApiException.notFound("REVIEW_NOT_FOUND", "Review not found.");
        }
        return ids.get(0);
    }

    private long ownBookId(AuthUser caller, long id) {
        List<Long> ids = jdbc.queryForList(
                "select book_id from reviews where id = ? and user_id = ? and status <> 'REMOVED'", Long.class, id, caller.id());
        if (ids.isEmpty()) {
            throw ApiException.notFound("REVIEW_NOT_FOUND", "Review not found.");
        }
        return ids.get(0);
    }

    private Long visibleAuthor(long id) {
        List<Long> ids = jdbc.queryForList("select user_id from reviews where id = ? and status = 'VISIBLE'", Long.class, id);
        if (ids.isEmpty()) {
            throw ApiException.notFound("REVIEW_NOT_FOUND", "Review not found.");
        }
        return ids.get(0);
    }

    private ReviewView get(long id, AuthUser caller) {
        return jdbc.queryForObject(select() + " where r.id = ?", view(), caller.id(), caller.id(), caller.id(), id);
    }

    /** Three leading ? parameters are the viewer id (mine / voted / reported). */
    private static String select() {
        return """
                select r.id, r.book_id, r.rating, r.title, r.comment, u.full_name, u.deleted_at is not null,
                       r.verified_purchase, r.helpful_count, r.created_at, r.updated_at,
                       r.user_id = ?,
                       exists (select 1 from review_votes v where v.review_id = r.id and v.user_id = ?),
                       exists (select 1 from review_reports p where p.review_id = r.id and p.user_id = ?)
                from reviews r join users u on u.id = r.user_id
                """;
    }

    private static RowMapper<ReviewView> view() {
        return (rs, i) -> {
            Instant created = rs.getTimestamp(10).toInstant();
            Instant updated = rs.getTimestamp(11).toInstant();
            return new ReviewView(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                    rs.getBoolean(7) ? "Former customer" : displayName(rs.getString(6)), rs.getBoolean(8),
                    rs.getInt(9), created, updated.isAfter(created.plusSeconds(60)), rs.getBoolean(12),
                    rs.getBoolean(13), rs.getBoolean(14));
        };
    }

    /** "Hemant Mhalsekar" → "Hemant M." (reviews never show full surnames or emails). */
    static String displayName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "Reader";
        }
        String[] parts = fullName.strip().split("\\s+");
        return parts.length == 1 ? parts[0] : parts[0] + " " + Character.toUpperCase(parts[parts.length - 1].charAt(0)) + ".";
    }

    private record Clean(String title, String comment) {
    }

    private static Clean clean(ReviewRequest req) {
        String title = req.title() == null ? null : stripHtml(req.title());
        String comment = stripHtml(req.comment());
        if (comment.length() < 10) {
            throw ApiException.badRequest("COMMENT_TOO_SHORT", "Write at least 10 characters (formatting doesn't count).");
        }
        return new Clean(title == null || title.isEmpty() ? null : title, comment);
    }

    /** Removes tags and squeezes spaces; React escapes everything anyway, this keeps stored text clean. */
    static String stripHtml(String s) {
        if (s == null) {
            return null;
        }
        String noTags = TAGS.matcher(s).replaceAll(" ");
        String[] lines = noTags.split("\\n", -1);
        StringBuilder b = new StringBuilder();
        for (String line : lines) {
            b.append(SPACES.matcher(line).replaceAll(" ").strip()).append('\n');
        }
        return b.toString().replaceAll("\\n{3,}", "\n\n").strip();
    }
}
