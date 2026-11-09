package com.bookmarked.order;

import com.bookmarked.catalog.Book;
import com.bookmarked.catalog.BookFormat;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.PageResponse;
import com.bookmarked.order.OrderEnums.FulfillmentStatus;
import com.bookmarked.order.OrderEnums.OrderStatus;
import com.bookmarked.order.OrderEnums.PaymentMethod;
import com.bookmarked.order.OrderEnums.PaymentStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Staff view of orders: search, and the full picture of one order. */
@Service
@Transactional(readOnly = true)
public class AdminOrderQueryService {

    public record Customer(Long id, String name, String email, String phone) {
    }

    public record AdminOrderRow(String orderNumber, OrderStatus status, PaymentMethod paymentMethod,
                                PaymentStatus paymentStatus, BigDecimal total, int itemCount, int toFulfil,
                                String customerName, String customerEmail, Instant createdAt) {
    }

    public record AdminOrderLine(Long id, Long variantId, String title, String slug, BookFormat format,
                                 BigDecimal unitPrice, int quantity, BigDecimal lineTotal,
                                 FulfillmentStatus fulfillmentStatus, Long shipmentId,
                                 List<OrderTimelines.TimelineEntry> timeline) {
    }

    public record AdminOrderDetail(String orderNumber, OrderStatus status, PaymentMethod paymentMethod,
                                   PaymentStatus paymentStatus, BigDecimal subtotal, BigDecimal discount,
                                   BigDecimal deliveryFee, BigDecimal codFee, BigDecimal total,
                                   Customer customer, OrderQueryService.ShippingAddress shipping,
                                   List<AdminOrderLine> items, List<OrderTimelines.ShipmentView> shipments,
                                   List<RefundService.RefundView> refunds, String razorpayOrderId,
                                   String razorpayPaymentId, Instant createdAt, Instant paidAt) {
    }

    private final PurchaseOrderRepository orders;
    private final OrderTimelines timelines;
    private final RefundService refunds;
    private final JdbcTemplate jdbc;

    public AdminOrderQueryService(PurchaseOrderRepository orders, OrderTimelines timelines, RefundService refunds,
                                  JdbcTemplate jdbc) {
        this.orders = orders;
        this.timelines = timelines;
        this.refunds = refunds;
        this.jdbc = jdbc;
    }

    public PageResponse<AdminOrderRow> search(String q, OrderStatus status, FulfillmentStatus itemStatus,
                                              int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be >= 0 and size between 1 and 100");
        }
        String pattern = null;
        if (q != null && !q.isBlank()) {
            String term = q.strip().toLowerCase(Locale.ROOT);
            if (term.length() > 100) {
                throw ApiException.badRequest("INVALID_QUERY", "Search is too long.");
            }
            pattern = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        }
        // MySQL's default collation is case-insensitive, so the lower-cased pattern also matches "BM-…"
        var result = orders.adminSearch(status, itemStatus, pattern, PageRequest.of(page, size));
        Map<Long, Customer> customers = customers(result.getContent().stream().map(PurchaseOrder::getUserId).distinct().toList());
        return PageResponse.of(result, o -> {
            Customer c = customers.get(o.getUserId());
            int toFulfil = (int) o.getItems().stream().filter(i -> i.getFulfillmentStatus() == FulfillmentStatus.PLACED
                    || i.getFulfillmentStatus() == FulfillmentStatus.PROCESSING
                    || i.getFulfillmentStatus() == FulfillmentStatus.SHIPPED).count();
            return new AdminOrderRow(o.getOrderNumber(), o.getStatus(), o.getPaymentMethod(), o.getPaymentStatus(),
                    o.getTotal(), o.getItems().stream().mapToInt(OrderItem::getQuantity).sum(), toFulfil,
                    c == null ? null : c.name(), c == null ? null : c.email(), o.getCreatedAt());
        });
    }

    public AdminOrderDetail detail(String orderNumber) {
        PurchaseOrder o = orders.findIdByOrderNumber(orderNumber).flatMap(orders::findById)
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Order not found."));
        return detail(o);
    }

    private AdminOrderDetail detail(PurchaseOrder o) {
        Map<Long, List<OrderTimelines.TimelineEntry>> history =
                timelines.forItems(o.getItems().stream().map(OrderItem::getId).toList());
        List<AdminOrderLine> lines = o.getItems().stream().map(i -> {
            Book book = i.getVariant().getBook();
            return new AdminOrderLine(i.getId(), i.getVariant().getId(), i.getTitleSnapshot(), book.getSlug(),
                    i.getFormat(), i.getUnitPrice(), i.getQuantity(), i.lineTotal(), i.getFulfillmentStatus(),
                    i.getShipmentId(), history.getOrDefault(i.getId(), List.of()));
        }).toList();
        OrderQueryService.ShippingAddress shipping = o.hasShipping()
                ? new OrderQueryService.ShippingAddress(o.getShipName(), o.getShipPhone(), o.getShipLine1(),
                o.getShipLine2(), o.getShipCity(), o.getShipState(), o.getShipPincode())
                : null;
        return new AdminOrderDetail(o.getOrderNumber(), o.getStatus(), o.getPaymentMethod(), o.getPaymentStatus(),
                o.getSubtotal(), o.getDiscount(), o.getDeliveryFee(), o.getCodFee(), o.getTotal(),
                customers(List.of(o.getUserId())).get(o.getUserId()), shipping, lines,
                timelines.shipments(o.getId()).values().stream()
                        .sorted(java.util.Comparator.comparing(OrderTimelines.ShipmentView::shippedAt)).toList(),
                refunds.forOrder(o.getId()), o.getRazorpayOrderId(), o.getRazorpayPaymentId(), o.getCreatedAt(),
                o.getPaidAt());
    }

    private Map<Long, Customer> customers(List<Long> ids) {
        Map<Long, Customer> result = new java.util.HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        String in = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        jdbc.query("select id, full_name, email, phone from users where id in (" + in + ")",
                rs -> {
                    result.put(rs.getLong(1), new Customer(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }, ids.toArray());
        return result;
    }
}
