package com.bookmarked.order;

import com.bookmarked.common.ApiException;
import com.bookmarked.common.PageResponse;
import com.bookmarked.notification.NotificationService;
import com.bookmarked.notification.NotificationService.Type;
import com.bookmarked.order.OrderEnums.PaymentStatus;
import com.bookmarked.payment.Money;
import com.bookmarked.payment.RazorpayGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Money going back to customers who paid online.
 * <p>
 * A refund row is written for every refund we owe, whatever Razorpay says:
 * PENDING (Razorpay accepted it), PROCESSED (Razorpay's refund.processed webhook),
 * or FAILED (Razorpay refused or was unreachable; staff retry from the Refunds page).
 * A gateway error never undoes the cancellation that caused the refund.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    public static final Set<String> STATUSES = Set.of("PENDING", "PROCESSED", "FAILED", "RETRYING");

    public record RefundView(Long id, String orderNumber, BigDecimal amount, String status, String reason,
                             String razorpayRefundId, String note, Instant createdAt, Instant updatedAt) {
    }

    private static final String SELECT = """
            select r.id, o.order_number, r.amount, r.status, r.reason, r.razorpay_refund_id, r.note, r.created_at, r.updated_at
            from refunds r join orders o on o.id = r.order_id
            """;
    private static final RowMapper<RefundView> ROW = (rs, i) -> new RefundView(rs.getLong(1), rs.getString(2),
            rs.getBigDecimal(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
            rs.getTimestamp(8).toInstant(), rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant());

    private final JdbcTemplate jdbc;
    private final RazorpayGateway razorpay;
    private final NotificationService notifications;
    private final Clock clock;

    public RefundService(JdbcTemplate jdbc, RazorpayGateway razorpay, NotificationService notifications, Clock clock) {
        this.jdbc = jdbc;
        this.razorpay = razorpay;
        this.notifications = notifications;
        this.clock = clock;
    }

    /**
     * Refunds {@code amount} of an online order's payment (in the caller's transaction)
     * and updates the order's payment status. Never throws for gateway problems.
     */
    public RefundView issue(PurchaseOrder order, BigDecimal amount, String reason) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive");
        }
        BigDecimal alreadyOwed = owed(order.getId());
        if (alreadyOwed.add(amount).compareTo(order.getTotal()) > 0) {
            throw new IllegalStateException("Refunds for " + order.getOrderNumber() + " would exceed the amount paid");
        }
        Instant now = clock.instant();
        String refundId = null;
        String status = "PENDING";
        String note = null;
        try {
            refundId = razorpay.refund(order.getRazorpayPaymentId(), Money.toPaise(amount));
        } catch (RazorpayGateway.GatewayException e) {
            status = "FAILED";
            note = shortReason(e);
            log.error("Refund of {} for {} failed; staff can retry it", amount, order.getOrderNumber(), e);
        }
        KeyHolder key = new GeneratedKeyHolder();
        String finalRefundId = refundId;
        String finalStatus = status;
        String finalNote = note;
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into refunds (order_id, razorpay_refund_id, amount, status, reason, note, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, order.getId());
            ps.setString(2, finalRefundId);
            ps.setBigDecimal(3, amount);
            ps.setString(4, finalStatus);
            ps.setString(5, reason);
            ps.setString(6, finalNote);
            ps.setTimestamp(7, Timestamp.from(now));
            ps.setTimestamp(8, Timestamp.from(now));
            return ps;
        }, key);
        Long id = key.getKey() == null ? null : key.getKey().longValue();

        order.setPaymentStatus(alreadyOwed.add(amount).compareTo(order.getTotal()) >= 0
                ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED);
        if ("FAILED".equals(status)) {
            notifications.notifyStaff("REFUND_ISSUE", Type.REFUND_FAILED, "Refund failed for " + order.getOrderNumber(),
                    OrderOperations.rupees(amount) + " couldn't be refunded automatically. Retry it from Refunds.",
                    "/admin/refunds");
        }
        return new RefundView(id, order.getOrderNumber(), amount, status, reason, refundId, note, now, now);
    }

    /** Everything owed to the customer so far, whether or not Razorpay has paid it out yet. */
    public BigDecimal owed(Long orderId) {
        BigDecimal sum = jdbc.queryForObject("select coalesce(sum(amount), 0) from refunds where order_id = ?",
                BigDecimal.class, orderId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    public List<RefundView> forOrder(Long orderId) {
        return jdbc.query(SELECT + " where r.order_id = ? order by r.created_at, r.id", ROW, orderId);
    }

    // ------------------------------------------------------------------
    // Staff
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<RefundView> list(String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be >= 0 and size between 1 and 100");
        }
        String filter = "";
        Object[] args;
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            String s = status.toUpperCase();
            if (!STATUSES.contains(s)) {
                throw ApiException.badRequest("INVALID_STATUS", "Unknown refund status.");
            }
            filter = " where r.status = ?";
            args = new Object[]{s, size, (long) page * size};
        } else {
            args = new Object[]{size, (long) page * size};
        }
        long total = filter.isEmpty()
                ? jdbc.queryForObject("select count(*) from refunds", Long.class)
                : jdbc.queryForObject("select count(*) from refunds r" + filter, Long.class, args[0]);
        List<RefundView> rows = jdbc.query(SELECT + filter + " order by r.created_at desc, r.id desc limit ? offset ?", ROW, args);
        return new PageResponse<>(rows, page, size, total, (int) ((total + size - 1) / size));
    }

    /** Tries a FAILED refund again. The conditional UPDATE stops two staff retrying the same one at once. */
    @Transactional
    public RefundView retry(long id) {
        Instant now = clock.instant();
        if (jdbc.update("update refunds set status = 'RETRYING', updated_at = ? where id = ? and status = 'FAILED'",
                Timestamp.from(now), id) == 0) {
            throw ApiException.conflict("REFUND_NOT_RETRYABLE", "Only failed refunds can be retried.");
        }
        record Pending(BigDecimal amount, String paymentId, String orderNumber) {
        }
        Pending p = jdbc.queryForObject("""
                select r.amount, o.razorpay_payment_id, o.order_number from refunds r join orders o on o.id = r.order_id
                where r.id = ?
                """, (rs, i) -> new Pending(rs.getBigDecimal(1), rs.getString(2), rs.getString(3)), id);
        try {
            String refundId = razorpay.refund(p.paymentId(), Money.toPaise(p.amount()));
            jdbc.update("update refunds set status = 'PENDING', razorpay_refund_id = ?, note = null, updated_at = ? where id = ?",
                    refundId, Timestamp.from(now), id);
            log.info("Refund {} for {} retried successfully", id, p.orderNumber());
        } catch (RazorpayGateway.GatewayException e) {
            jdbc.update("update refunds set status = 'FAILED', note = ?, updated_at = ? where id = ?",
                    shortReason(e), Timestamp.from(now), id);
            log.warn("Refund {} for {} failed again", id, p.orderNumber(), e);
        }
        return jdbc.queryForObject(SELECT + " where r.id = ?", ROW, id);
    }

    /** From Razorpay's refund.processed webhook: the money is on its way to the customer. */
    public void markProcessed(String razorpayRefundId) {
        if (jdbc.update("update refunds set status = 'PROCESSED', updated_at = ? where razorpay_refund_id = ? and status <> 'PROCESSED'",
                Timestamp.from(clock.instant()), razorpayRefundId) == 0) {
            return;   // unknown or already processed
        }
        record Done(long userId, String orderNumber, BigDecimal amount) {
        }
        Done d = jdbc.queryForObject("""
                select o.user_id, o.order_number, r.amount from refunds r join orders o on o.id = r.order_id
                where r.razorpay_refund_id = ?
                """, (rs, i) -> new Done(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3)), razorpayRefundId);
        notifications.notifyWithEmail(d.userId(), Type.REFUND_PROCESSED,
                "Refund of " + OrderOperations.rupees(d.amount()) + " processed",
                "Your refund for order " + d.orderNumber() + " is on its way. It usually shows in your account within 5–7 working days.",
                "/orders/" + d.orderNumber(), "View order");
    }

    private static String shortReason(Exception e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String msg = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        return msg.length() > 250 ? msg.substring(0, 250) : msg;
    }
}
