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
import java.util.List;

/** The customer's view of their orders. Orders are always looked up by number AND owner. */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    private final PurchaseOrderRepository orders;
    private final Clock clock;

    public OrderQueryService(PurchaseOrderRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
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
                            BigDecimal lineTotal, FulfillmentStatus fulfillmentStatus) {
    }

    public record OrderDetail(String orderNumber, OrderStatus status, PaymentMethod paymentMethod,
                              PaymentStatus paymentStatus, BigDecimal subtotal, BigDecimal discount,
                              BigDecimal deliveryFee, BigDecimal codFee, BigDecimal total,
                              ShippingAddress shipping, List<OrderLine> items, Instant createdAt, Instant paidAt,
                              Instant reservedUntil, boolean awaitingPayment) {
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
        ShippingAddress shipping = o.hasShipping()
                ? new ShippingAddress(o.getShipName(), o.getShipPhone(), o.getShipLine1(), o.getShipLine2(),
                o.getShipCity(), o.getShipState(), o.getShipPincode())
                : null;
        List<OrderLine> lines = o.getItems().stream().map(i -> {
            Book book = i.getVariant().getBook();
            return new OrderLine(i.getId(), i.getVariant().getId(), i.getTitleSnapshot(), book.getSlug(),
                    book.getCoverImageUrl(), i.getFormat(), i.getMrp(), i.getUnitPrice(), i.getQuantity(),
                    i.lineTotal(), i.getFulfillmentStatus());
        }).toList();
        return new OrderDetail(o.getOrderNumber(), o.getStatus(), o.getPaymentMethod(), o.getPaymentStatus(),
                o.getSubtotal(), o.getDiscount(), o.getDeliveryFee(), o.getCodFee(), o.getTotal(), shipping, lines,
                o.getCreatedAt(), o.getPaidAt(), awaiting(o) ? o.getReservedUntil() : null, awaiting(o));
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

    private boolean awaiting(PurchaseOrder o) {
        return o.getStatus() == OrderStatus.PENDING_PAYMENT && o.getReservedUntil() != null
                && o.getReservedUntil().isAfter(clock.instant());
    }
}
