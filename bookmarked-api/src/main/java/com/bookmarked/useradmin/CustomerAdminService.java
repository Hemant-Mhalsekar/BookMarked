package com.bookmarked.useradmin;

import com.bookmarked.audit.AuditService;
import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.auth.security.SessionGuard;
import com.bookmarked.auth.token.RefreshToken;
import com.bookmarked.auth.token.RefreshTokenRepository;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Customers as staff with USER_MANAGE see them: search, a profile with history, block and sign-out. */
@Service
public class CustomerAdminService {

    public record CustomerRow(Long id, String fullName, String email, String phone, boolean emailVerified,
                              boolean enabled, boolean deleted, int orders, BigDecimal spent, Instant createdAt,
                              Instant lastLoginAt) {
    }

    public record OrderRow(String orderNumber, String status, String paymentMethod, String paymentStatus,
                           BigDecimal total, Instant createdAt) {
    }

    public record LoginRow(Instant at, boolean success, String ipAddress, String device) {
    }

    public record CustomerDetail(CustomerRow customer, int returns, int reviews, int libraryBooks, int activeSessions,
                                 List<OrderRow> recentOrders, List<LoginRow> recentLogins) {
    }

    /** Spend counts orders that went through, minus what was refunded. */
    private static final String ROW = """
            select u.id, u.full_name, u.email, u.phone, u.email_verified, u.enabled, u.deleted_at is not null,
                   (select count(*) from orders o where o.user_id = u.id and o.status in ('CONFIRMED', 'COMPLETED', 'CANCELLED')),
                   (select coalesce(sum(o.total), 0) from orders o where o.user_id = u.id and o.status in ('CONFIRMED', 'COMPLETED'))
                   - (select coalesce(sum(r.amount), 0) from refunds r join orders o on o.id = r.order_id
                      where o.user_id = u.id and o.status in ('CONFIRMED', 'COMPLETED')),
                   u.created_at, u.last_login_at
            from users u
            """;
    private static final RowMapper<CustomerRow> MAPPER = (rs, i) -> new CustomerRow(rs.getLong(1), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7), rs.getInt(8),
            rs.getBigDecimal(9), rs.getTimestamp(10).toInstant(),
            rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant());

    private final JdbcTemplate jdbc;
    private final RefreshTokenRepository refreshTokens;
    private final SessionGuard sessionGuard;
    private final AuditService audit;
    private final Clock clock;

    public CustomerAdminService(JdbcTemplate jdbc, RefreshTokenRepository refreshTokens, SessionGuard sessionGuard,
                                AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.refreshTokens = refreshTokens;
        this.sessionGuard = sessionGuard;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerRow> search(String q, String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be >= 0 and size between 1 and 100");
        }
        StringBuilder where = new StringBuilder(" where u.account_type = 'CUSTOMER'");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String term = "%" + q.strip().toLowerCase().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            where.append(" and (lower(u.full_name) like ? escape '!' or lower(u.email) like ? escape '!' or u.phone like ? escape '!')");
            args.add(term);
            args.add(term);
            args.add(term);
        }
        switch (status == null ? "ALL" : status.toUpperCase()) {
            case "ACTIVE" -> where.append(" and u.enabled = true and u.deleted_at is null");
            case "BLOCKED" -> where.append(" and u.enabled = false and u.deleted_at is null");
            case "DELETED" -> where.append(" and u.deleted_at is not null");
            case "ALL" -> {
            }
            default -> throw ApiException.badRequest("INVALID_STATUS", "Use ALL, ACTIVE, BLOCKED or DELETED.");
        }
        long total = jdbc.queryForObject("select count(*) from users u" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<CustomerRow> rows = jdbc.query(ROW + where + " order by u.created_at desc limit ? offset ?", MAPPER,
                pageArgs.toArray());
        return new PageResponse<>(rows, page, size, total, (int) ((total + size - 1) / size));
    }

    @Transactional(readOnly = true)
    public CustomerDetail detail(long id) {
        CustomerRow c = row(id);
        Timestamp now = Timestamp.from(clock.instant());
        List<OrderRow> orders = jdbc.query("""
                select order_number, status, payment_method, payment_status, total, created_at from orders
                where user_id = ? order by created_at desc limit 10
                """, (rs, i) -> new OrderRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getBigDecimal(5), rs.getTimestamp(6).toInstant()), id);
        List<LoginRow> logins = jdbc.query("""
                select created_at, success, ip_address, user_agent from login_history
                where user_id = ? order by created_at desc limit 10
                """, (rs, i) -> new LoginRow(rs.getTimestamp(1).toInstant(), rs.getBoolean(2), rs.getString(3),
                rs.getString(4)), id);
        return new CustomerDetail(c,
                count("""
                        select count(*) from returns r join order_items oi on oi.id = r.order_item_id
                        join orders o on o.id = oi.order_id where o.user_id = ?
                        """, id),
                count("select count(*) from reviews where user_id = ?", id),
                count("select count(*) from user_library where user_id = ?", id),
                jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked = false and expires_at > ?",
                        Integer.class, id, now),
                orders, logins);
    }

    @Transactional
    public CustomerDetail setEnabled(AuthUser staff, long id, boolean enabled, String ip) {
        CustomerRow c = row(id);
        if (c.deleted()) {
            throw ApiException.conflict("ACCOUNT_DELETED", "This account was deleted.");
        }
        if (c.enabled() != enabled) {
            jdbc.update("update users set enabled = ?, updated_at = ? where id = ?", enabled, Timestamp.from(clock.instant()), id);
            if (!enabled) {
                refreshTokens.revokeAllForUser(id, RefreshToken.REASON_ADMIN, clock.instant());
                sessionGuard.forgetAll();
            }
            audit.record(staff.id(), enabled ? "Customer.unblock" : "Customer.block", "CUSTOMERS", String.valueOf(id),
                    Map.of("enabled", c.enabled()), Map.of("enabled", enabled), ip);
        }
        return detail(id);
    }

    @Transactional
    public CustomerDetail signOut(AuthUser staff, long id, String ip) {
        row(id);
        int ended = refreshTokens.revokeAllForUser(id, RefreshToken.REASON_ADMIN, clock.instant());
        sessionGuard.forgetAll();
        audit.record(staff.id(), "Customer.signOut", "CUSTOMERS", String.valueOf(id), null, Map.of("sessionsEnded", ended), ip);
        return detail(id);
    }

    private CustomerRow row(long id) {
        List<CustomerRow> rows = jdbc.query(ROW + " where u.id = ? and u.account_type = 'CUSTOMER'", MAPPER, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("CUSTOMER_NOT_FOUND", "Customer not found.");
        }
        return rows.get(0);
    }

    private int count(String sql, long id) {
        Integer n = jdbc.queryForObject(sql, Integer.class, id);
        return n == null ? 0 : n;
    }
}
