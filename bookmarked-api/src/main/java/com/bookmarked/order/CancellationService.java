package com.bookmarked.order;

import com.bookmarked.catalog.InventoryMovement;
import com.bookmarked.common.ApiException;
import com.bookmarked.notification.NotificationService;
import com.bookmarked.notification.NotificationService.Type;
import com.bookmarked.order.OrderEnums.FulfillmentStatus;
import com.bookmarked.order.OrderEnums.OrderStatus;
import com.bookmarked.order.OrderEnums.PaymentMethod;
import com.bookmarked.order.OrderEnums.PaymentStatus;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cancelling physical items before they ship, by the customer or by staff.
 * <ul>
 *   <li>Only PLACED or PROCESSING physical items; e-books and shipped items can't be cancelled.</li>
 *   <li>Stock goes back (CANCEL in the ledger) and every item gets a history entry.</li>
 *   <li>Online orders are refunded what was paid for those items; the delivery fee too,
 *       once no physical item is left. COD orders have nothing to refund.</li>
 *   <li>Repeating a request is harmless: items already cancelled are skipped, and the
 *       order row is locked so two requests can't refund the same item twice.</li>
 * </ul>
 */
@Service
public class CancellationService {

    private static final Logger log = LoggerFactory.getLogger(CancellationService.class);

    /** No item ids = every item that can still be cancelled. */
    public record CancelRequest(@Size(max = 50) List<Long> itemIds,
                                @Size(max = 200, message = "Keep the reason under 200 characters") String reason) {
    }

    public record Result(PurchaseOrder order, List<OrderItem> cancelled, BigDecimal refunded) {
    }

    private final PurchaseOrderRepository orders;
    private final OrderOperations ops;
    private final RefundService refunds;
    private final NotificationService notifications;

    public CancellationService(PurchaseOrderRepository orders, OrderOperations ops, RefundService refunds,
                               NotificationService notifications) {
        this.orders = orders;
        this.ops = ops;
        this.refunds = refunds;
        this.notifications = notifications;
    }

    /**
     * @param staffId who cancelled, or null when the customer did
     */
    @Transactional
    public Result cancel(Long orderId, Collection<Long> itemIds, String reason, Long staffId) {
        PurchaseOrder order = orders.lockById(orderId)
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Order not found."));
        boolean wholeOrder = itemIds == null || itemIds.isEmpty();
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT || order.getStatus() == OrderStatus.EXPIRED) {
            throw ApiException.conflict("NOT_CANCELLABLE", notCancellableMessage(order, order.getItems()));
        }

        List<OrderItem> requested;
        if (wholeOrder) {
            requested = order.getItems();
        } else {
            Map<Long, OrderItem> byId = order.getItems().stream()
                    .collect(Collectors.toMap(OrderItem::getId, Function.identity()));
            requested = itemIds.stream().distinct().map(id -> {
                OrderItem item = byId.get(id);
                if (item == null) {
                    throw ApiException.notFound("ITEM_NOT_FOUND", "That item isn't part of this order.");
                }
                return item;
            }).toList();
        }

        List<OrderItem> toCancel = requested.stream().filter(OrderItem::isCancellable).toList();
        if (toCancel.isEmpty()) {
            List<OrderItem> physical = requested.stream().filter(i -> i.getFormat().isPhysical()).toList();
            boolean repeat = !physical.isEmpty()
                    && physical.stream().allMatch(i -> i.getFulfillmentStatus() == FulfillmentStatus.CANCELLED);
            if (repeat) {
                return new Result(order, List.of(), BigDecimal.ZERO);   // a repeat of an earlier request
            }
            throw ApiException.conflict("NOT_CANCELLABLE", notCancellableMessage(order, requested));
        }
        if (!wholeOrder) {
            requested.stream()
                    .filter(i -> !i.isCancellable() && i.getFulfillmentStatus() != FulfillmentStatus.CANCELLED)
                    .findFirst()
                    .ifPresent(i -> {
                        throw ApiException.conflict("NOT_CANCELLABLE", notCancellableMessage(order, List.of(i)));
                    });
        }
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw ApiException.conflict("NOT_CANCELLABLE", notCancellableMessage(order, requested));
        }

        String note = reason == null || reason.isBlank()
                ? (staffId == null ? "Cancelled by you" : "Cancelled by BookMarked")
                : (staffId == null ? "Cancelled by you: " : "Cancelled by BookMarked: ") + reason.strip();
        for (OrderItem item : toCancel) {
            item.setFulfillmentStatus(FulfillmentStatus.CANCELLED);
            ops.history(item, FulfillmentStatus.CANCELLED, note, staffId);
        }
        ops.releaseItems(order, toCancel, InventoryMovement.Reason.CANCEL, note, staffId);

        BigDecimal refund = refundFor(order, toCancel);
        if (refund.signum() > 0) {
            refunds.issue(order, refund, "CANCELLATION");
        }
        ops.refreshOrderStatus(order);
        orders.flush();

        tellCustomer(order, toCancel, refund, staffId != null ? reason : null);
        log.info("Cancelled {} item(s) of {} (refund {}) by {}", toCancel.size(), order.getOrderNumber(), refund,
                staffId == null ? "customer" : "staff " + staffId);
        return new Result(order, toCancel, refund);
    }

    /** What goes back for these items: their price, plus delivery once no physical item is left. */
    BigDecimal refundFor(PurchaseOrder order, List<OrderItem> cancelled) {
        boolean paidOnline = order.getPaymentMethod() == PaymentMethod.ONLINE
                && (order.getPaymentStatus() == PaymentStatus.PAID
                || order.getPaymentStatus() == PaymentStatus.PARTIALLY_REFUNDED);
        if (!paidOnline) {
            return BigDecimal.ZERO;
        }
        BigDecimal amount = cancelled.stream().map(OrderItem::paidAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean noPhysicalLeft = order.getItems().stream()
                .filter(i -> i.getFormat().isPhysical())
                .allMatch(i -> i.getFulfillmentStatus() == FulfillmentStatus.CANCELLED);
        if (noPhysicalLeft) {
            amount = amount.add(order.getDeliveryFee());
        }
        BigDecimal left = order.getTotal().subtract(refunds.owed(order.getId()));
        return amount.min(left).max(BigDecimal.ZERO);
    }

    private void tellCustomer(PurchaseOrder order, List<OrderItem> cancelled, BigDecimal refund, String staffReason) {
        boolean all = order.getStatus() == OrderStatus.CANCELLED;
        String titles = cancelled.stream().map(OrderItem::getTitleSnapshot).distinct().collect(Collectors.joining(", "));
        StringBuilder msg = new StringBuilder(all ? "Your order has been cancelled" : "Cancelled: " + titles);
        if (staffReason != null && !staffReason.isBlank()) {
            msg.append(" (").append(staffReason.strip()).append(")");
        }
        msg.append(". ");
        if (refund.signum() > 0) {
            msg.append(OrderOperations.rupees(refund)).append(" will be refunded to your original payment method within 5–7 working days.");
        } else if (order.getPaymentMethod() == PaymentMethod.COD) {
            msg.append("Nothing was charged, as this was Cash on Delivery.");
        }
        notifications.notifyWithEmail(order.getUserId(), Type.ORDER_CANCELLED,
                (all ? "Order " : "Items cancelled from order ") + order.getOrderNumber(),
                msg.toString().strip(), "/orders/" + order.getOrderNumber(), "View order");
    }

    private static String notCancellableMessage(PurchaseOrder order, List<OrderItem> items) {
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            return "This order isn't paid yet. If you don't pay, it's released automatically in a few minutes.";
        }
        if (order.getStatus() == OrderStatus.EXPIRED) {
            return "This order was never paid, so there's nothing to cancel.";
        }
        for (OrderItem i : items) {
            if (!i.getFormat().isPhysical()) {
                return "E-books are delivered instantly and can't be cancelled.";
            }
            switch (i.getFulfillmentStatus()) {
                case SHIPPED -> {
                    return "\"" + i.getTitleSnapshot() + "\" has already shipped. You can return it within 7 days of delivery.";
                }
                case DELIVERED, RETURN_REQUESTED, RETURNED -> {
                    return "\"" + i.getTitleSnapshot() + "\" has been delivered. You can request a return instead.";
                }
                default -> {
                }
            }
        }
        return "Nothing in this order can be cancelled now.";
    }
}
