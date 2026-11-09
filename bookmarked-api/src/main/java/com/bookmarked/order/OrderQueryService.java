package com.bookmarked.order;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.catalog.Book;
import com.bookmarked.catalog.BookFormat;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.CustomerOnly;
import com.bookmarked.common.PageResponse;
import com.bookmarked.order.OrderEnums.FulfillmentStatus;
import com.bookmarked.order.OrderEnums.OrderStatus;
import com.bookmarked.order.OrderEnums.PaymentMethod;
import com.bookmarked.order.OrderEnums.PaymentStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The customer's view of their orders. Orders are always looked up by number AND owner. */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    private final PurchaseOrderRepository orders;
    private final OrderTimelines timelines;
    private final RefundService refunds;
    private final ReturnService returns;
    private final Clock clock;

    public OrderQueryService(PurchaseOrderRepository orders, OrderTimelines timelines, RefundService refunds,
                             ReturnService returns, Clock clock) {
        this.orders = orders;
        this.timelines = timelines;
        this.refunds = refunds;
        this.returns = returns;
        this.clock = clock;
    }

    public PageResponse<OrderSummary> list(AuthUser caller, Tab tab, int page, int size) {
        CustomerOnly.require(caller);
        if (page < 0 || size < 1 || size > 50) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be >= 0 and size between 1 and 50");
        }
        if (tab == null || tab == Tab.ALL) {
            return list(caller, page, size);
        }
        return PageResponse.of(orders.findByUserIdAndStatusInOrderByCreatedAtDesc(caller.id(), tab.statuses,
                PageRequest.of(page, size)), this::summary);
    }

    public record ItemThumb(String title, String slug, String coverImageUrl, BookFormat format) {
    }

    public record OrderSummary(String orderNumber, OrderStatus status, PaymentMethod paymentMethod,
                               PaymentStatus paymentStatus, BigDecimal total, int itemCount, Instant createdAt,
                               boolean awaitingPayment, List<ItemThumb> items) {
    }

    public record ShippingAddress(String name, String phone, String line1, String line2, String city, String state,
                                  String pincode) {
    }

    public record OrderLine(Long id, Long variantId, String title, String slug, String coverImageUrl,
                            BookFormat format, BigDecimal mrp, BigDecimal unitPrice, int quantity,
                            BigDecimal lineTotal, FulfillmentStatus fulfillmentStatus, boolean cancellable,
                            OrderTimelines.ShipmentView shipment, List<OrderTimelines.TimelineEntry> timeline,
                            boolean returnable, Instant returnBy, ReturnService.CustomerReturn returnRequest) {
    }

    public record RefundLine(BigDecimal amount, String status, String reason, Instant createdAt) {
    }

    public record OrderDetail(String orderNumber, OrderStatus status, PaymentMethod paymentMethod,
                              PaymentStatus paymentStatus, BigDecimal subtotal, BigDecimal discount,
                              BigDecimal deliveryFee, BigDecimal codFee, BigDecimal total,
                              ShippingAddress shipping, List<OrderLine> items, Instant createdAt, Instant paidAt,
                              Instant reservedUntil, boolean awaitingPayment, boolean cancellable,
                              boolean invoiceAvailable, List<RefundLine> refunds) {
    }

    /** Tabs on My Orders. */
    public enum Tab {
        ALL(EnumSet.allOf(OrderStatus.class)),
        IN_PROGRESS(EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED)),
        DELIVERED(EnumSet.of(OrderStatus.COMPLETED)),
        CANCELLED(EnumSet.of(OrderStatus.CANCELLED, OrderStatus.EXPIRED));

        final Set<OrderStatus> statuses;

        Tab(Set<OrderStatus> statuses) {
            this.statuses = statuses;
        }
    }

    public PageResponse<OrderSummary> list(AuthUser caller, int page, int size) {
        CustomerOnly.require(caller);
        if (page < 0 || size < 1 || size > 50) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be >= 0 and size between 1 and 50");
        }
        return PageResponse.of(orders.findByUserIdOrderByCreatedAtDesc(caller.id(), PageRequest.of(page, size)),
                this::summary);
    }

    public OrderDetail detail(AuthUser caller, String orderNumber) {
        CustomerOnly.require(caller);
        PurchaseOrder o = orders.findByOrderNumberAndUserId(orderNumber, caller.id())
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Order not found."));
        return detail(o);
    }

    /** Builds the customer view of an already-authorised order. */
    public OrderDetail detail(PurchaseOrder o) {
        Map<Long, List<OrderTimelines.TimelineEntry>> history =
                timelines.forItems(o.getItems().stream().map(OrderItem::getId).toList());
        Map<Long, OrderTimelines.ShipmentView> shipments = timelines.shipments(o.getId());
        Map<Long, ReturnService.CustomerReturn> returnRequests =
                returns.forItems(o.getItems().stream().map(OrderItem::getId).toList());
        boolean open = o.getStatus() == OrderStatus.CONFIRMED;
        ShippingAddress shipping = o.hasShipping()
                ? new ShippingAddress(o.getShipName(), o.getShipPhone(), o.getShipLine1(), o.getShipLine2(),
                o.getShipCity(), o.getShipState(), o.getShipPincode())
                : null;
        List<OrderLine> lines = o.getItems().stream().map(i -> {
            Book book = i.getVariant().getBook();
            return new OrderLine(i.getId(), i.getVariant().getId(), i.getTitleSnapshot(), book.getSlug(),
                    book.getCoverImageUrl(), i.getFormat(), i.getMrp(), i.getUnitPrice(), i.getQuantity(),
                    i.lineTotal(), i.getFulfillmentStatus(), open && i.isCancellable(),
                    i.getShipmentId() == null ? null : shipments.get(i.getShipmentId()),
                    history.getOrDefault(i.getId(), List.of()),
                    returns.returnable(i, returnRequests.containsKey(i.getId())),
                    returns.returnBy(i), returnRequests.get(i.getId()));
        }).toList();
        List<RefundLine> refundLines = refunds.forOrder(o.getId()).stream()
                .map(r -> new RefundLine(r.amount(), "RETRYING".equals(r.status()) ? "PENDING" : r.status(),
                        r.reason(), r.createdAt()))
                .toList();
        return new OrderDetail(o.getOrderNumber(), o.getStatus(), o.getPaymentMethod(), o.getPaymentStatus(),
                o.getSubtotal(), o.getDiscount(), o.getDeliveryFee(), o.getCodFee(), o.getTotal(), shipping, lines,
                o.getCreatedAt(), o.getPaidAt(), awaiting(o) ? o.getReservedUntil() : null, awaiting(o),
                lines.stream().anyMatch(OrderLine::cancellable), invoiceAvailable(o), refundLines);
    }

    private OrderSummary summary(PurchaseOrder o) {
        List<ItemThumb> thumbs = o.getItems().stream().limit(3).map(i -> {
            Book book = i.getVariant().getBook();
            return new ItemThumb(i.getTitleSnapshot(), book.getSlug(), book.getCoverImageUrl(), i.getFormat());
        }).toList();
        int count = o.getItems().stream().mapToInt(OrderItem::getQuantity).sum();
        return new OrderSummary(o.getOrderNumber(), o.getStatus(), o.getPaymentMethod(), o.getPaymentStatus(),
                o.getTotal(), count, o.getCreatedAt(), awaiting(o), thumbs);
    }

    /** Invoices exist for orders that were actually placed (paid, or COD confirmed). */
    public static boolean invoiceAvailable(PurchaseOrder o) {
        return o.getStatus() == OrderStatus.CONFIRMED || o.getStatus() == OrderStatus.COMPLETED
                || (o.getStatus() == OrderStatus.CANCELLED && o.getPaidAt() != null);
    }

    private boolean awaiting(PurchaseOrder o) {
        return o.getStatus() == OrderStatus.PENDING_PAYMENT && o.getReservedUntil() != null
                && o.getReservedUntil().isAfter(clock.instant());
    }
}
