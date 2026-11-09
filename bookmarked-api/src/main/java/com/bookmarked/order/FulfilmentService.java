package com.bookmarked.order;

import com.bookmarked.common.ApiException;
import com.bookmarked.notification.NotificationService;
import com.bookmarked.notification.NotificationService.Type;
import com.bookmarked.order.OrderEnums.FulfillmentStatus;
import com.bookmarked.order.OrderEnums.OrderStatus;
import com.bookmarked.order.OrderEnums.PaymentMethod;
import com.bookmarked.order.OrderEnums.PaymentStatus;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Staff moving physical items through PLACED → PROCESSING → SHIPPED → DELIVERED.
 * Only the next step is allowed (no skipping, no going back); asking for a step an
 * item has already reached is a harmless no-op. Every change is recorded in the
 * item history with the staff member's id, and the customer is told.
 */
@Service
public class FulfilmentService {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM").withZone(ZoneId.of("Asia/Kolkata"));

    public record ItemsRequest(@NotEmpty(message = "Choose at least one item") @Size(max = 50) List<Long> itemIds) {
    }

    public record ShipRequest(
            @NotEmpty(message = "Choose at least one item") @Size(max = 50) List<Long> itemIds,
            @NotBlank(message = "Enter the courier") @Size(max = 60) String courierName,
            @NotBlank(message = "Enter the tracking number")
            @Pattern(regexp = "^[A-Za-z0-9-]{4,60}$", message = "4–60 letters, digits or dashes") String trackingNumber,
            @NotNull(message = "Choose the expected delivery date")
            @FutureOrPresent(message = "The expected date can't be in the past") LocalDate expectedDelivery) {
    }

    private final PurchaseOrderRepository orders;
    private final OrderOperations ops;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public FulfilmentService(PurchaseOrderRepository orders, OrderOperations ops, NotificationService notifications,
                             JdbcTemplate jdbc, Clock clock) {
        this.orders = orders;
        this.ops = ops;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public PurchaseOrder markProcessing(Long orderId, Collection<Long> itemIds, Long staffId) {
        PurchaseOrder order = lockConfirmed(orderId);
        List<OrderItem> moved = advance(order, itemIds, FulfillmentStatus.PLACED, FulfillmentStatus.PROCESSING,
                "Being packed", staffId);
        if (!moved.isEmpty()) {
            notifications.notify(order.getUserId(), Type.ORDER_PROCESSING, "We're packing your order",
                    titles(moved) + " " + (moved.size() == 1 ? "is" : "are") + " being packed.",
                    "/orders/" + order.getOrderNumber());
        }
        return order;
    }

    @Transactional
    public PurchaseOrder ship(Long orderId, ShipRequest req, Long staffId) {
        PurchaseOrder order = lockConfirmed(orderId);
        List<OrderItem> items = pick(order, req.itemIds());
        List<OrderItem> toShip = items.stream()
                .filter(i -> i.getFulfillmentStatus() != FulfillmentStatus.SHIPPED).toList();
        requireAll(toShip, FulfillmentStatus.PROCESSING, FulfillmentStatus.SHIPPED);
        if (toShip.isEmpty()) {
            return order;   // already shipped
        }

        Instant now = clock.instant();
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into shipments (order_id, courier_name, tracking_number, shipped_at, expected_delivery) values (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, order.getId());
            ps.setString(2, req.courierName().strip());
            ps.setString(3, req.trackingNumber().strip().toUpperCase());
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setDate(5, Date.valueOf(req.expectedDelivery()));
            return ps;
        }, key);
        Long shipmentId = key.getKey().longValue();

        String note = "Shipped with " + req.courierName().strip() + " · tracking " + req.trackingNumber().strip().toUpperCase();
        for (OrderItem item : toShip) {
            item.setFulfillmentStatus(FulfillmentStatus.SHIPPED);
            item.setShipmentId(shipmentId);
            ops.history(item, FulfillmentStatus.SHIPPED, note, staffId);
        }
        orders.flush();
        notifications.notifyWithEmail(order.getUserId(), Type.ORDER_SHIPPED, "Your books are on the way",
                titles(toShip) + " shipped with " + req.courierName().strip() + " (tracking "
                        + req.trackingNumber().strip().toUpperCase() + "). Expected by "
                        + DAY.format(req.expectedDelivery().atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant()) + ".",
                "/orders/" + order.getOrderNumber(), "Track your order");
        log.info("Order {}: {} item(s) shipped by staff {}", order.getOrderNumber(), toShip.size(), staffId);
        return order;
    }

    @Transactional
    public PurchaseOrder markDelivered(Long orderId, Collection<Long> itemIds, Long staffId) {
        PurchaseOrder order = lockConfirmed(orderId);
        Instant now = clock.instant();
        List<OrderItem> moved = advance(order, itemIds, FulfillmentStatus.SHIPPED, FulfillmentStatus.DELIVERED,
                "Delivered", staffId);
        moved.forEach(i -> i.setDeliveredAt(now));
        ops.refreshOrderStatus(order);
        orders.flush();
        if (!moved.isEmpty()) {
            notifications.notifyWithEmail(order.getUserId(), Type.ORDER_DELIVERED, "Delivered: " + titles(moved),
                    "Enjoy your books! If something's wrong, you can request a return within 7 days.",
                    "/orders/" + order.getOrderNumber(), "View order");
        }
        return order;
    }

    /** COD money handed over by the courier; only once every remaining physical item is delivered. */
    @Transactional
    public PurchaseOrder markCodCollected(Long orderId, Long staffId) {
        PurchaseOrder order = orders.lockById(orderId)
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Order not found."));
        if (order.getPaymentMethod() != PaymentMethod.COD) {
            throw ApiException.conflict("NOT_COD", "This order was paid online.");
        }
        if (order.getPaymentStatus() == PaymentStatus.COLLECTED) {
            return order;
        }
        List<OrderItem> open = order.getItems().stream()
                .filter(i -> i.getFulfillmentStatus() != FulfillmentStatus.CANCELLED).toList();
        boolean delivered = !open.isEmpty() && open.stream().allMatch(i -> switch (i.getFulfillmentStatus()) {
            case DELIVERED, RETURN_REQUESTED, RETURNED -> true;
            default -> false;
        });
        if (!delivered) {
            throw ApiException.conflict("NOT_DELIVERED", "Mark the items delivered before recording the cash.");
        }
        order.setPaymentStatus(PaymentStatus.COLLECTED);
        if (order.getPaidAt() == null) {
            order.setPaidAt(clock.instant());
        }
        log.info("COD collected for {} by staff {}", order.getOrderNumber(), staffId);
        return order;
    }

    // ------------------------------------------------------------------

    private PurchaseOrder lockConfirmed(Long orderId) {
        PurchaseOrder order = orders.lockById(orderId)
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Order not found."));
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw ApiException.conflict("ORDER_NOT_OPEN", switch (order.getStatus()) {
                case PENDING_PAYMENT -> "This order isn't paid yet.";
                case COMPLETED -> "Everything in this order is already delivered or cancelled.";
                default -> "This order is " + order.getStatus().name().toLowerCase().replace('_', ' ') + ".";
            });
        }
        return order;
    }

    /** Moves items from {@code from} to {@code to}; items already at {@code to} are skipped. */
    private List<OrderItem> advance(PurchaseOrder order, Collection<Long> itemIds, FulfillmentStatus from,
                                    FulfillmentStatus to, String note, Long staffId) {
        List<OrderItem> items = pick(order, itemIds);
        List<OrderItem> toMove = items.stream().filter(i -> i.getFulfillmentStatus() != to).toList();
        requireAll(toMove, from, to);
        for (OrderItem item : toMove) {
            item.setFulfillmentStatus(to);
            ops.history(item, to, note, staffId);
        }
        orders.flush();
        return toMove;
    }

    private static List<OrderItem> pick(PurchaseOrder order, Collection<Long> itemIds) {
        Map<Long, OrderItem> byId = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getId, Function.identity()));
        return itemIds.stream().distinct().map(id -> {
            OrderItem item = byId.get(id);
            if (item == null) {
                throw ApiException.notFound("ITEM_NOT_FOUND", "That item isn't part of this order.");
            }
            if (!item.getFormat().isPhysical()) {
                throw ApiException.conflict("INVALID_TRANSITION", "E-books are delivered automatically.");
            }
            return item;
        }).toList();
    }

    private static void requireAll(List<OrderItem> items, FulfillmentStatus from, FulfillmentStatus to) {
        for (OrderItem item : items) {
            if (item.getFulfillmentStatus() != from) {
                throw ApiException.conflict("INVALID_TRANSITION", "\"" + item.getTitleSnapshot() + "\" is "
                        + label(item.getFulfillmentStatus()) + "; it can only be marked " + label(to)
                        + " when it is " + label(from) + ".");
            }
        }
    }

    private static String label(FulfillmentStatus s) {
        return switch (s) {
            case PLACED -> "placed";
            case PROCESSING -> "packing";
            case SHIPPED -> "shipped";
            case DELIVERED -> "delivered";
            case CANCELLED -> "cancelled";
            case RETURN_REQUESTED -> "being returned";
            case RETURNED -> "returned";
        };
    }

    private static String titles(List<OrderItem> items) {
        List<String> names = items.stream().map(OrderItem::getTitleSnapshot).distinct().toList();
        return names.size() <= 2 ? String.join(" and ", names) : names.get(0) + " and " + (names.size() - 1) + " more";
    }
}
