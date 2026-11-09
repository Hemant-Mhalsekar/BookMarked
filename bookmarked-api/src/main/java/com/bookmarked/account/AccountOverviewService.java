package com.bookmarked.account;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.common.CustomerOnly;
import com.bookmarked.library.LibraryService;
import com.bookmarked.order.OrderQueryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/** Everything the dashboard's first screen needs, in one call. */
@Service
public class AccountOverviewService {

    public record Stats(long orders, long library, long bookmarks, long reviews) {
    }

    /** Something waiting for the customer, with where to do it. */
    public record PendingAction(String type, String text, String link) {
    }

    public record Overview(String firstName, boolean emailVerified, Stats stats,
                           List<LibraryService.LibraryBook> continueReading,
                           List<OrderQueryService.OrderSummary> recentOrders, List<PendingAction> pending) {
    }

    private final JdbcTemplate jdbc;
    private final LibraryService library;
    private final OrderQueryService orders;
    private final Clock clock;

    public AccountOverviewService(JdbcTemplate jdbc, LibraryService library, OrderQueryService orders, Clock clock) {
        this.jdbc = jdbc;
        this.library = library;
        this.orders = orders;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Overview overview(AuthUser caller) {
        CustomerOnly.require(caller);
        long id = caller.id();
        record Me(String name, boolean verified) {
        }
        Me me = jdbc.queryForObject("select full_name, email_verified from users where id = ?",
                (rs, i) -> new Me(rs.getString(1), rs.getBoolean(2)), id);

        Stats stats = new Stats(
                count("select count(*) from orders where user_id = ? and status <> 'EXPIRED'", id),
                count("select count(*) from user_library where user_id = ?", id),
                count("select count(*) from wishlist_items where user_id = ?", id),
                count("select count(*) from reviews where user_id = ? and status <> 'REMOVED'", id));

        List<LibraryService.LibraryBook> reading = library.list(caller).stream()
                .filter(b -> b.lastPage() != null && b.available()).limit(3).toList();
        List<OrderQueryService.OrderSummary> recent = orders.list(caller, 0, 3).content();

        List<PendingAction> pending = new ArrayList<>();
        if (!me.verified()) {
            pending.add(new PendingAction("VERIFY_EMAIL", "Verify your email to use Cash on Delivery and get order updates.", "/account/profile"));
        }
        jdbc.query("""
                select order_number from orders where user_id = ? and status = 'PENDING_PAYMENT' and reserved_until > ?
                order by created_at desc
                """, rs -> {
            pending.add(new PendingAction("COMPLETE_PAYMENT", "Complete payment for order " + rs.getString(1)
                    + " before the books are released.", "/orders/" + rs.getString(1)));
        }, id, Timestamp.from(clock.instant()));
        jdbc.query("""
                select o.order_number, oi.title_snapshot from returns r
                join order_items oi on oi.id = r.order_item_id join orders o on o.id = oi.order_id
                where o.user_id = ? and r.status = 'APPROVED'
                """, rs -> {
            pending.add(new PendingAction("RETURN_PICKUP", "Keep \"" + rs.getString(2)
                    + "\" packed: the courier is coming to collect it.", "/orders/" + rs.getString(1)));
        }, id);
        long waiting = count("""
                select count(distinct bv.book_id) from order_items oi
                join orders o on o.id = oi.order_id join book_variants bv on bv.id = oi.variant_id
                where o.user_id = ? and oi.delivered_at is not null
                  and not exists (select 1 from reviews r where r.user_id = o.user_id and r.book_id = bv.book_id)
                """, id);
        if (waiting > 0) {
            pending.add(new PendingAction("REVIEW", waiting == 1 ? "1 book is waiting for your review."
                    : waiting + " books are waiting for your review.", "/account/reviews"));
        }

        String first = me.name() == null ? "there" : me.name().strip().split("\\s+")[0];
        return new Overview(first, me.verified(), stats, reading, recent, pending);
    }

    private long count(String sql, long id) {
        Long n = jdbc.queryForObject(sql, Long.class, id);
        return n == null ? 0 : n;
    }
}
