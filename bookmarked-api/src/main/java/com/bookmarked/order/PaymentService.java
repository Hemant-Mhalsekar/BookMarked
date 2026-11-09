package com.bookmarked.order;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.catalog.InventoryMovement;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.CustomerOnly;
import com.bookmarked.order.CheckoutDtos.VerifyRequest;
import com.bookmarked.order.OrderEnums.OrderStatus;
import com.bookmarked.order.OrderEnums.PaymentStatus;
import com.bookmarked.payment.Money;
import com.bookmarked.payment.RazorpayGateway;
import com.bookmarked.payment.RazorpayProperties;
import com.bookmarked.payment.Signatures;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

/**
 * Confirms payments. Two independent paths report the same payment:
 * <ol>
 *   <li>the browser, right after Razorpay Checkout succeeds ({@link #verify}), and</li>
 *   <li>Razorpay's webhook ({@link #handleWebhook}), which still arrives if the customer
 *       closes the tab.</li>
 * </ol>
 * Both end in {@link #confirmPayment}, whose conditional UPDATE lets exactly one of
 * them fulfil the order. Duplicate webhook deliveries are dropped by a unique event id.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    public enum Outcome {
        CONFIRMED, ALREADY_CONFIRMED, CONFIRMED_LATE, REFUNDED, AMOUNT_MISMATCH, IGNORED
    }

    private final PurchaseOrderRepository orders;
    private final OrderOperations ops;
    private final RazorpayGateway razorpay;
    private final RazorpayProperties props;
    private final OrderQueryService orderQueries;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PaymentService(PurchaseOrderRepository orders, OrderOperations ops, RazorpayGateway razorpay,
                          RazorpayProperties props, OrderQueryService orderQueries, ObjectMapper json,
                          JdbcTemplate jdbc, Clock clock) {
        this.orders = orders;
        this.ops = ops;
        this.razorpay = razorpay;
        this.props = props;
        this.orderQueries = orderQueries;
        this.json = json;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Path 1: the browser
    // ------------------------------------------------------------------

    /** Business errors (e.g. "refunded") must not roll back what was recorded before them. */
    @Transactional(noRollbackFor = ApiException.class)
    public OrderQueryService.OrderDetail verify(AuthUser caller, VerifyRequest req) {
        CustomerOnly.require(caller);
        PurchaseOrder order = orders.findByRazorpayOrderId(req.razorpayOrderId())
                .filter(o -> o.getUserId().equals(caller.id()))
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Order not found."));
        if (!Signatures.checkoutSignatureValid(req.razorpayOrderId(), req.razorpayPaymentId(),
                req.razorpaySignature(), props.keySecret())) {
            log.warn("Invalid checkout signature for order {}", order.getOrderNumber());
            throw ApiException.badRequest("INVALID_SIGNATURE", "We couldn't verify this payment.");
        }
        String orderNumber = order.getOrderNumber();
        Outcome outcome = confirmPayment(order.getId(), req.razorpayPaymentId(), null);
        if (outcome == Outcome.REFUNDED) {
            throw ApiException.conflict("PAYMENT_REFUNDED",
                    "Your payment arrived after the books were reserved for someone else, so we've refunded it.");
        }
        return orderQueries.detail(caller, orderNumber);
    }

    // ------------------------------------------------------------------
    // Path 2: the webhook
    // ------------------------------------------------------------------

    @Transactional
    public Outcome handleWebhook(String rawBody, String signature, String eventIdHeader) {
        if (!props.webhookConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WEBHOOK_NOT_CONFIGURED", "Webhook secret not set.");
        }
        if (!Signatures.matches(rawBody, props.webhookSecret(), signature)) {
            log.warn("Rejected webhook with an invalid signature");
            throw ApiException.badRequest("INVALID_SIGNATURE", "Invalid signature.");
        }
        JsonNode root;
        try {
            root = json.readTree(rawBody);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("MALFORMED_REQUEST", "Invalid JSON.");
        }
        String event = root.path("event").asText("");
        JsonNode payment = root.path("payload").path("payment").path("entity");
        String paymentId = textOrNull(payment, "id");
        String razorpayOrderId = textOrNull(payment, "order_id");
        long amount = payment.path("amount").asLong(-1);

        Long orderId = razorpayOrderId == null ? null
                : orders.findByRazorpayOrderId(razorpayOrderId).map(PurchaseOrder::getId).orElse(null);
        String eventId = eventIdHeader != null && !eventIdHeader.isBlank() ? eventIdHeader.trim()
                : event + ":" + (paymentId != null ? paymentId : textOrNull(root.path("payload").path("refund").path("entity"), "id"));
        if (eventId.length() > 60) {
            eventId = eventId.substring(0, 60);
        }

        int inserted = jdbc.update("""
                insert ignore into payment_events (order_id, event_type, razorpay_event_id, payload, received_at)
                values (?, ?, ?, ?, ?)
                """, orderId, event.length() > 40 ? event.substring(0, 40) : event, eventId, rawBody,
                Timestamp.from(clock.instant()));
        if (inserted == 0) {
            log.info("Duplicate webhook {} ignored", eventId);
            return Outcome.IGNORED;
        }

        switch (event) {
            case "payment.captured", "order.paid" -> {
                if (orderId != null && paymentId != null) {
                    return confirmPayment(orderId, paymentId, amount >= 0 ? amount : null);
                }
            }
            case "payment.failed" -> {
                if (orderId != null) {
                    markFailed(orderId);
                }
            }
            case "refund.processed" -> {
                String refundId = textOrNull(root.path("payload").path("refund").path("entity"), "id");
                if (refundId != null) {
                    jdbc.update("update refunds set status = 'PROCESSED' where razorpay_refund_id = ?", refundId);
                }
            }
            default -> log.debug("Webhook event {} not handled", event);
        }
        return Outcome.IGNORED;
    }

    // ------------------------------------------------------------------
    // The single confirmation point
    // ------------------------------------------------------------------

    /**
     * @param amountPaise what Razorpay says was paid (webhook), or null when the
     *                    signature already binds the payment to our order (browser)
     */
    @Transactional
    public Outcome confirmPayment(Long orderId, String paymentId, Long amountPaise) {
        PurchaseOrder before = orders.findById(orderId).orElseThrow();
        if (amountPaise != null && amountPaise != Money.toPaise(before.getTotal())) {
            log.error("Amount mismatch on {}: paid {} paise, expected {}", before.getOrderNumber(), amountPaise,
                    Money.toPaise(before.getTotal()));
            return Outcome.AMOUNT_MISMATCH;
        }

        Instant now = clock.instant();
        int won = orders.markPaid(orderId, paymentId, now, OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED,
                PaymentStatus.PAID);
        PurchaseOrder order = orders.findById(orderId).orElseThrow();   // fresh: markPaid cleared the context
        if (won == 1) {
            ops.fulfil(order);
            log.info("Order {} paid ({})", order.getOrderNumber(), paymentId);
            return Outcome.CONFIRMED;
        }

        return switch (order.getStatus()) {
            case CONFIRMED, COMPLETED -> {
                if (!paymentId.equals(order.getRazorpayPaymentId())) {
                    log.error("Second payment {} for already-paid order {}; refund it from the dashboard",
                            paymentId, order.getOrderNumber());
                }
                yield Outcome.ALREADY_CONFIRMED;
            }
            case EXPIRED -> latePayment(order, paymentId, now);
            default -> Outcome.IGNORED;
        };
    }

    /**
     * Money arrived after the reservation expired. If the stock is still there,
     * take it again and confirm; otherwise refund automatically.
     */
    private Outcome latePayment(PurchaseOrder order, String paymentId, Instant now) {
        order.setRazorpayPaymentId(paymentId);
        order.setPaidAt(now);
        if (ops.tryRetakeStock(order)) {
            order.setStatus(OrderStatus.CONFIRMED);
            order.setPaymentStatus(PaymentStatus.PAID);
            orders.flush();
            ops.fulfil(order);
            log.info("Late payment for {} confirmed; stock re-reserved", order.getOrderNumber());
            return Outcome.CONFIRMED_LATE;
        }

        long amount = Money.toPaise(order.getTotal());
        String refundId = null;
        String status = "PENDING";
        try {
            refundId = razorpay.refund(paymentId, amount);
        } catch (RazorpayGateway.GatewayException e) {
            status = "FAILED";
            log.error("Automatic refund failed for {} ({}); retry from admin", order.getOrderNumber(), paymentId, e);
        }
        jdbc.update("insert into refunds (order_id, razorpay_refund_id, amount, status, reason, created_at) values (?, ?, ?, ?, 'LATE_PAYMENT', ?)",
                order.getId(), refundId, order.getTotal(), status, Timestamp.from(now));
        order.setStatus(OrderStatus.CANCELLED);
        order.setPaymentStatus(PaymentStatus.REFUNDED);
        log.warn("Late payment for {} refunded: stock no longer available", order.getOrderNumber());
        return Outcome.REFUNDED;
    }

    private void markFailed(Long orderId) {
        orders.findById(orderId)
                .filter(o -> o.getStatus() == OrderStatus.PENDING_PAYMENT)
                .ifPresent(o -> o.setPaymentStatus(PaymentStatus.FAILED));
    }

    // ------------------------------------------------------------------
    // Reservation expiry
    // ------------------------------------------------------------------

    /** Expires unpaid online orders past their 15 minutes and puts the stock back. */
    @Transactional
    public int expireOverdue() {
        Instant now = clock.instant();
        int expired = 0;
        for (PurchaseOrder due : orders.findTop100ByStatusAndReservedUntilBefore(OrderStatus.PENDING_PAYMENT, now)) {
            Long id = due.getId();
            if (orders.expire(id, now, OrderStatus.PENDING_PAYMENT, OrderStatus.EXPIRED) == 1) {
                PurchaseOrder order = orders.findById(id).orElseThrow();
                ops.releaseStock(order, InventoryMovement.Reason.RELEASE, "Payment window expired");
                ops.cancelItems(order, "Payment not received in time");
                expired++;
            }
        }
        if (expired > 0) {
            log.info("Expired {} unpaid orders", expired);
        }
        return expired;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
