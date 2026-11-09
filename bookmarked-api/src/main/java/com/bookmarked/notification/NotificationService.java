package com.bookmarked.notification;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.PageResponse;
import com.bookmarked.mail.MailService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * In-app notifications plus the matching email.
 * <p>
 * The row is written in the caller's transaction, so it disappears if that
 * transaction rolls back. The email is sent only <i>after</i> a successful commit,
 * so nobody is told "your order shipped" for a change that never happened.
 */
@Service
public class NotificationService {

    /** What a notification is about; the frontend picks an icon from it. */
    public enum Type {
        ORDER_PLACED, PAYMENT_FAILED, ORDER_PROCESSING, ORDER_SHIPPED, ORDER_DELIVERED, ORDER_CANCELLED,
        REFUND_PROCESSED, REFUND_FAILED, RETURN_UPDATE,
        // staff
        NEW_ORDER, LOW_STOCK, NEW_RETURN
    }

    public record Item(Long id, String type, String title, String message, String link, boolean read, Instant createdAt) {
    }

    public record UnreadCount(long unread) {
    }

    private final JdbcTemplate jdbc;
    private final MailService mail;
    private final Clock clock;

    public NotificationService(JdbcTemplate jdbc, MailService mail, Clock clock) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /** In-app only. */
    public void notify(long userId, Type type, String title, String message, String link) {
        insert(userId, type, title, message, link);
    }

    /** In-app, plus an email after commit. */
    public void notifyWithEmail(long userId, Type type, String title, String message, String link, String linkLabel) {
        insert(userId, type, title, message, link);
        List<MailService.Recipient> to = jdbc.query(
                "select email, full_name from users where id = ? and enabled = true and deleted_at is null",
                (rs, i) -> new MailService.Recipient(rs.getString(1), rs.getString(2)), userId);
        if (!to.isEmpty()) {
            afterCommit(() -> mail.sendNotification(to.get(0), title, message, link, linkLabel));
        }
    }

    /** Every active staff member holding {@code permission} (in-app only). */
    public void notifyStaff(String permission, Type type, String title, String message, String link) {
        List<Long> staff = jdbc.queryForList("""
                select u.id from users u
                join role_permissions rp on rp.role_id = u.role_id
                join permissions p on p.id = rp.permission_id
                where p.name = ? and u.account_type = 'STAFF' and u.enabled = true and u.deleted_at is null
                """, Long.class, permission);
        staff.forEach(id -> insert(id, type, title, message, link));
    }

    private void insert(long userId, Type type, String title, String message, String link) {
        jdbc.update("insert into notifications (user_id, type, title, message, link, is_read, created_at) values (?, ?, ?, ?, ?, false, ?)",
                userId, type.name(), cut(title, 120), cut(message, 500), cut(link, 300), Timestamp.from(clock.instant()));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ------------------------------------------------------------------
    // Reading (any signed-in user, always their own)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<Item> list(AuthUser caller, boolean unreadOnly, int page, int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be >= 0 and size between 1 and 50");
        }
        String filter = unreadOnly ? " and is_read = false" : "";
        long total = jdbc.queryForObject("select count(*) from notifications where user_id = ?" + filter,
                Long.class, caller.id());
        List<Item> items = jdbc.query("select id, type, title, message, link, is_read, created_at from notifications "
                        + "where user_id = ?" + filter + " order by created_at desc, id desc limit ? offset ?",
                (rs, i) -> new Item(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getBoolean(6), rs.getTimestamp(7).toInstant()),
                caller.id(), size, (long) page * size);
        int pages = (int) ((total + size - 1) / size);
        return new PageResponse<>(items, page, size, total, pages);
    }

    @Transactional(readOnly = true)
    public UnreadCount unread(AuthUser caller) {
        return new UnreadCount(jdbc.queryForObject(
                "select count(*) from notifications where user_id = ? and is_read = false", Long.class, caller.id()));
    }

    @Transactional
    public void markRead(AuthUser caller, long id) {
        // The user id in the WHERE clause means nobody can touch someone else's notifications
        if (jdbc.update("update notifications set is_read = true where id = ? and user_id = ?", id, caller.id()) == 0) {
            throw ApiException.notFound("NOTIFICATION_NOT_FOUND", "Notification not found.");
        }
    }

    @Transactional
    public UnreadCount markAllRead(AuthUser caller) {
        jdbc.update("update notifications set is_read = true where user_id = ? and is_read = false", caller.id());
        return new UnreadCount(0);
    }
}
