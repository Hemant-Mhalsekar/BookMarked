package com.bookmarked.order;

import com.bookmarked.catalog.InventoryMovement;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.PageResponse;
import com.bookmarked.config.AppProperties;
import com.bookmarked.notification.NotificationService;
import com.bookmarked.notification.NotificationService.Type;
import com.bookmarked.order.OrderEnums.FulfillmentStatus;
import com.bookmarked.order.OrderEnums.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 7-day returns for physical books (refund only, no replacements).
 * <pre>
 * REQUESTED → APPROVED → PICKED_UP → RECEIVED → REFUNDED
 *     └──────────┴──→ REJECTED (with a reason the customer sees)
 * </pre>
 * On RECEIVED staff choose restock (stock goes back, RETURN in the ledger) or write off.
 * Online payments are refunded through Razorpay; COD orders are refunded by staff to
 * the customer's UPI ID and the UPI reference is recorded. Every step locks the order
 * row, accepts only the next status, and treats a repeat as a no-op.
 */
@Service
public class ReturnService {

    private static final Logger log = LoggerFactory.getLogger(ReturnService.class);

    public enum Reason {
        DAMAGED, WRONG_ITEM, PRINTING_DEFECT, NOT_AS_DESCRIBED, CHANGED_MIND
    }

    public enum Status {
        REQUESTED, APPROVED, PICKED_UP, RECEIVED, REFUNDED, REJECTED
    }

    /** UPI IDs look like name@bank. */
    public static final String UPI_REGEX = "^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9]{1,30}$";

    public record ReturnRequest(
            @NotNull(message = "Choose a reason") Reason reason,
            @Size(max = 1000, message = "Keep it under 1000 characters") String comment,
            @Pattern(regexp = UPI_REGEX, message = "Enter a UPI ID like name@okbank") String refundUpiId) {
    }

    public record RejectRequest(@NotNull @Size(min = 3, max = 500, message = "Tell the customer why (3–500 characters)") String note) {
    }

    public record ReceiveRequest(@NotNull(message = "Choose restock or write off") Boolean restock,
                                 @Size(max = 500) String note) {
    }

    public record RefundRequest(@Size(max = 60) String upiReference) {
    }

    /** One return, as staff see it. */
    public record ReturnView(Long id, String status, String reason, String comment, BigDecimal refundAmount,
                             String refundMethod, String refundUpiId, String upiReference, String adminNote,
                             Boolean restocked, Instant requestedAt, Instant updatedAt,
                             String orderNumber, Long orderItemId, String title, String format, int quantity,
                             String customerName, String customerEmail) {
    }

    /** The customer's view, attached to the item on their order page. */
    public record CustomerReturn(Long id, String status, String reason, BigDecimal refundAmount, String adminNote,
                                 Instant requestedAt, Instant updatedAt) {
    }

    private static final String SELECT = """
            select r.id, r.status, r.reason, r.comment, r.refund_amount, o.payment_method, r.refund_upi_id,
                   r.upi_reference, r.admin_note, r.restocked, r.requested_at, r.updated_at,
                   o.order_number, oi.id, oi.title_snapshot, oi.format, oi.quantity, u.full_name, u.email, o.id
            from returns r
            join order_items oi on oi.id = r.order_item_id
            join orders o on o.id = oi.order_id
            join users u on u.id = o.user_id
            """;
    private static final RowMapper<ReturnView> ROW = (rs, i) -> new ReturnView(rs.getLong(1), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getBigDecimal(5), "COD".equals(rs.getString(6)) ? "UPI" : "RAZORPAY",
            rs.getString(7), rs.getString(8), rs.getString(9), (Boolean) rs.getObject(10),
            rs.getTimestamp(11).toInstant(), rs.getTimestamp(12).toInstant(), rs.getString(13), rs.getLong(14),
            rs.getString(15), rs.getString(16), rs.getInt(17), rs.getString(18), rs.getString(19));

    private final PurchaseOrderRepository orders;
    private final OrderOperations ops;
    private final RefundService refunds;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration window;

    public ReturnService(PurchaseOrderRepository orders, OrderOperations ops, RefundService refunds,
                         NotificationService notifications, JdbcTemplate jdbc, Clock clock, AppProperties props) {
        this.orders = orders;
        this.ops = ops;
        this.refunds = refunds;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
        this.window = Duration.ofDays(props.business().returnWindowDays());
    }

    // ------------------------------------------------------------------
    // Customer
    // ------------------------------------------------------------------

    /** Last moment a delivered item can be returned, or null if it can't be. */
    public Instant returnBy(OrderItem item) {
        if (!item.getFormat().isPhysical() || item.getDeliveredAt() == null) {
            return null;
        }
        return item.getDeliveredAt().plus(window);
    }

    public boolean returnable(OrderItem item, boolean alreadyRequested) {
        Instant by = returnBy(item);
        return !alreadyRequested && by != null && item.getFulfillmentStatus() == FulfillmentStatus.DELIVERED
                && clock.instant().isBefore(by);
    }

    @Transactional
    public CustomerReturn request(Long orderId, Long itemId, ReturnRequest req) {
        PurchaseOrder order = orders.lockById(orderId)
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Order not found."));
        OrderItem item = order.getItems().stream().filter(i -> i.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("ITEM_NOT_FOUND", "That item isn't part of this order."));

        Optional<CustomerReturn> existing = forItem(itemId);
        if (existing.isPresent()) {
            if (existing.get().status().equals(Status.REJECTED.name())) {
                throw ApiException.conflict("RETURN_EXISTS", "A return for this book was already reviewed and declined.");
            }
            return existing.get();   // a repeat of the same request
        }
        if (!item.getFormat().isPhysical()) {
            throw ApiException.conflict("NOT_RETURNABLE", "E-books can't be returned.");
        }
        if (item.getFulfillmentStatus() != FulfillmentStatus.DELIVERED) {
            throw ApiException.conflict("NOT_RETURNABLE", "Only delivered books can be returned.");
        }
        if (!clock.instant().isBefore(returnBy(item))) {
            throw ApiException.conflict("RETURN_WINDOW_CLOSED",
                    "Returns are accepted within " + window.toDays() + " days of delivery, and that window has closed.");
        }
        boolean cod = order.getPaymentMethod() == PaymentMethod.COD;
        if (cod && (req.refundUpiId() == null || req.refundUpiId().isBlank())) {
            throw ApiException.badRequest("UPI_REQUIRED", "This was Cash on Delivery, so we need a UPI ID to send your refund to.");
        }

        BigDecimal amount = item.paidAmount();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                insert into returns (order_item_id, reason, comment, status, refund_amount, refund_upi_id, requested_at, updated_at)
                values (?, ?, ?, 'REQUESTED', ?, ?, ?, ?)
                """, itemId, req.reason().name(), blankToNull(req.comment()), amount,
                cod ? req.refundUpiId().strip() : null, now, now);
        item.setFulfillmentStatus(FulfillmentStatus.RETURN_REQUESTED);
        ops.history(item, FulfillmentStatus.RETURN_REQUESTED, "Return requested: " + label(req.reason()));
        ops.refreshOrderStatus(order);
        orders.flush();

        String link = "/orders/" + order.getOrderNumber();
        notifications.notifyWithEmail(order.getUserId(), Type.RETURN_UPDATE, "Return requested: " + item.getTitleSnapshot(),
                "We'll review it within 2 working days and arrange a pickup. Your refund of "
                        + OrderOperations.rupees(amount) + " follows once the book reaches us.", link, "View order");
        notifications.notifyStaff("RETURN_MANAGE", Type.NEW_RETURN, "Return requested on " + order.getOrderNumber(),
                item.getTitleSnapshot() + " · " + label(req.reason()), "/admin/returns");
        log.info("Return requested for item {} of {}", itemId, order.getOrderNumber());
        return forItem(itemId).orElseThrow();
    }

    public Optional<CustomerReturn> forItem(Long itemId) {
        return forItems(List.of(itemId)).values().stream().findFirst();
    }

    public Map<Long, CustomerReturn> forItems(List<Long> itemIds) {
        Map<Long, CustomerReturn> result = new java.util.HashMap<>();
        if (itemIds.isEmpty()) {
            return result;
        }
        String in = String.join(",", java.util.Collections.nCopies(itemIds.size(), "?"));
        jdbc.query("select id, order_item_id, status, reason, refund_amount, admin_note, requested_at, updated_at "
                        + "from returns where order_item_id in (" + in + ")",
                rs -> {
                    result.put(rs.getLong(2), new CustomerReturn(rs.getLong(1), rs.getString(3), rs.getString(4),
                            rs.getBigDecimal(5), rs.getString(6), rs.getTimestamp(7).toInstant(),
                            rs.getTimestamp(8).toInstant()));
                }, itemIds.toArray());
        return result;
    }

    // ------------------------------------------------------------------
    // Staff
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<ReturnView> list(String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be >= 0 and size between 1 and 100");
        }
        Status filter = null;
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            try {
                filter = Status.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("INVALID_STATUS", "Unknown return status.");
            }
        }
        String where = filter == null ? "" : " where r.status = ?";
        Object[] args = filter == null ? new Object[]{size, (long) page * size}
                : new Object[]{filter.name(), size, (long) page * size};
        long total = filter == null ? jdbc.queryForObject("select count(*) from returns", Long.class)
                : jdbc.queryForObject("select count(*) from returns r" + where, Long.class, filter.name());
        List<ReturnView> rows = jdbc.query(SELECT + where + " order by r.requested_at desc, r.id desc limit ? offset ?", ROW, args);
        return new PageResponse<>(rows, page, size, total, (int) ((total + size - 1) / size));
    }

    @Transactional(readOnly = true)
    public ReturnView get(long id) {
        List<ReturnView> rows = jdbc.query(SELECT + " where r.id = ?", ROW, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("RETURN_NOT_FOUND", "Return not found.");
        }
        return rows.get(0);
    }

    @Transactional
    public void approve(long id, Long staffId) {
        Locked l = lock(id);
        if (move(l, Set.of(Status.REQUESTED), Status.APPROVED, null, staffId)) {
            tell(l, "Return approved: " + l.item().getTitleSnapshot(),
                    "Our courier will collect the book in the next few days. Keep it packed and ready.");
        }
    }

    @Transactional
    public void reject(long id, String note, Long staffId) {
        Locked l = lock(id);
        if (move(l, Set.of(Status.REQUESTED, Status.APPROVED), Status.REJECTED, note.strip(), staffId)) {
            l.item().setFulfillmentStatus(FulfillmentStatus.DELIVERED);
            ops.history(l.item(), FulfillmentStatus.DELIVERED, "Return declined: " + note.strip(), staffId);
            ops.refreshOrderStatus(l.order());
            orders.flush();
            tell(l, "Return declined: " + l.item().getTitleSnapshot(), note.strip());
        }
    }

    @Transactional
    public void pickedUp(long id, Long staffId) {
        Locked l = lock(id);
        if (move(l, Set.of(Status.APPROVED), Status.PICKED_UP, null, staffId)) {
            tell(l, "Return picked up: " + l.item().getTitleSnapshot(),
                    "The courier has the book. We'll refund you once it reaches our warehouse.");
        }
    }

    @Transactional
    public void received(long id, boolean restock, String note, Long staffId) {
        Locked l = lock(id);
        if (move(l, Set.of(Status.PICKED_UP), Status.RECEIVED, blankToNull(note), staffId)) {
            jdbc.update("update returns set restocked = ? where id = ?", restock, id);
            if (restock) {
                ops.releaseItems(l.order(), List.of(l.item()), InventoryMovement.Reason.RETURN,
                        "Returned, restocked (return " + id + ")", staffId);
            }
        }
    }

    /** Online orders: Razorpay refund. COD orders: staff paid by UPI and give the reference. */
    @Transactional
    public void refund(long id, String upiReference, Long staffId) {
        Locked l = lock(id);
        boolean cod = l.order().getPaymentMethod() == PaymentMethod.COD;
        if (cod && (upiReference == null || upiReference.isBlank())) {
            throw ApiException.badRequest("UPI_REFERENCE_REQUIRED",
                    "Send the refund to the customer's UPI ID, then enter the UPI reference here.");
        }
        if (!move(l, Set.of(Status.RECEIVED), Status.REFUNDED, null, staffId)) {
            return;
        }
        BigDecimal amount = l.refundAmount();
        if (cod) {
            jdbc.update("update returns set upi_reference = ? where id = ?", upiReference.strip(), id);
            refunds.recordManual(l.order(), amount, "RETURN", "UPI to " + l.upiId() + " · ref " + upiReference.strip());
        } else {
            refunds.issue(l.order(), amount, "RETURN");
        }
        l.item().setFulfillmentStatus(FulfillmentStatus.RETURNED);
        ops.history(l.item(), FulfillmentStatus.RETURNED, "Returned and refunded", staffId);
        ops.refreshOrderStatus(l.order());
        orders.flush();
        tell(l, "Refund of " + OrderOperations.rupees(amount) + " for your return",
                cod ? "We've sent it to " + l.upiId() + " (UPI reference " + upiReference.strip() + ")."
                        : "It's on its way to your original payment method and usually arrives within 5–7 working days.");
    }

    // ------------------------------------------------------------------

    private record Locked(long id, PurchaseOrder order, OrderItem item, Status status, BigDecimal refundAmount,
                          String upiId) {
    }

    /** Locks the order the return belongs to, then reads the return's current state. */
    private Locked lock(long id) {
        List<Long> orderIds = jdbc.queryForList("""
                select oi.order_id from returns r join order_items oi on oi.id = r.order_item_id where r.id = ?
                """, Long.class, id);
        if (orderIds.isEmpty()) {
            throw ApiException.notFound("RETURN_NOT_FOUND", "Return not found.");
        }
        PurchaseOrder order = orders.lockById(orderIds.get(0)).orElseThrow();
        record Row(long itemId, String status, BigDecimal amount, String upi) {
        }
        Row row = jdbc.queryForObject("select order_item_id, status, refund_amount, refund_upi_id from returns where id = ?",
                (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4)), id);
        OrderItem item = order.getItems().stream().filter(i -> i.getId() == row.itemId()).findFirst().orElseThrow();
        return new Locked(id, order, item, Status.valueOf(row.status()), row.amount(), row.upi());
    }

    /** @return true if the status changed, false if it was already {@code to} (a repeat) */
    private boolean move(Locked l, Set<Status> from, Status to, String note, Long staffId) {
        if (l.status() == to) {
            return false;
        }
        if (!from.contains(l.status())) {
            throw ApiException.conflict("INVALID_TRANSITION", "This return is " + l.status().name().toLowerCase()
                    .replace('_', ' ') + " and can't be marked " + to.name().toLowerCase().replace('_', ' ') + ".");
        }
        jdbc.update("update returns set status = ?, admin_note = coalesce(?, admin_note), handled_by = ?, updated_at = ? where id = ?",
                to.name(), note, staffId, Timestamp.from(clock.instant()), l.id());
        log.info("Return {} {} → {} by staff {}", l.id(), l.status(), to, staffId);
        return true;
    }

    private void tell(Locked l, String title, String message) {
        notifications.notifyWithEmail(l.order().getUserId(), Type.RETURN_UPDATE, title, message,
                "/orders/" + l.order().getOrderNumber(), "View order");
    }

    static String label(Reason reason) {
        return switch (reason) {
            case DAMAGED -> "Damaged";
            case WRONG_ITEM -> "Wrong item";
            case PRINTING_DEFECT -> "Missing pages / printing defect";
            case NOT_AS_DESCRIBED -> "Not as described";
            case CHANGED_MIND -> "Changed my mind";
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
