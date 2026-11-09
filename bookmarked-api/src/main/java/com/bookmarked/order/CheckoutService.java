package com.bookmarked.order;

import com.bookmarked.address.Address;
import com.bookmarked.address.AddressService;
import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.cart.Cart;
import com.bookmarked.cart.CartRepository;
import com.bookmarked.cart.CartService;
import com.bookmarked.cart.PricingService;
import com.bookmarked.catalog.BookFormat;
import com.bookmarked.catalog.BookVariant;
import com.bookmarked.catalog.BookVariantRepository;
import com.bookmarked.catalog.InventoryMovement;
import com.bookmarked.catalog.StockStatus;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.CustomerOnly;
import com.bookmarked.config.AppProperties;
import com.bookmarked.library.LibraryLookup;
import com.bookmarked.order.CheckoutDtos.CheckoutRequest;
import com.bookmarked.order.CheckoutDtos.CheckoutResponse;
import com.bookmarked.order.CheckoutDtos.Prefill;
import com.bookmarked.order.CheckoutDtos.Source;
import com.bookmarked.order.OrderEnums.OrderStatus;
import com.bookmarked.order.OrderEnums.PaymentMethod;
import com.bookmarked.order.OrderEnums.PaymentStatus;
import com.bookmarked.payment.Money;
import com.bookmarked.payment.RazorpayGateway;
import com.bookmarked.payment.RazorpayProperties;
import com.bookmarked.user.User;
import com.bookmarked.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turns Your Stack (or a single "Buy now" format) into an order.
 * <p>
 * Everything happens in one transaction: validate, price on the server, snapshot
 * the address, take stock atomically, then either create the Razorpay order
 * (online: stock held for 15 minutes) or confirm straight away (Cash on Delivery).
 * Any failure, including Razorpay being down, rolls the stock back.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,40}$");
    private static final DateTimeFormatter NUMBER_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("Asia/Kolkata"));

    private final PurchaseOrderRepository orders;
    private final CartRepository carts;
    private final BookVariantRepository variants;
    private final UserRepository users;
    private final AddressService addresses;
    private final PricingService pricing;
    private final LibraryLookup library;
    private final OrderOperations ops;
    private final RazorpayGateway razorpay;
    private final RazorpayProperties razorpayProps;
    private final AppProperties.Business business;
    private final Clock clock;

    public CheckoutService(PurchaseOrderRepository orders, CartRepository carts, BookVariantRepository variants,
                           UserRepository users, AddressService addresses, PricingService pricing,
                           LibraryLookup library, OrderOperations ops, RazorpayGateway razorpay,
                           RazorpayProperties razorpayProps, AppProperties props, Clock clock) {
        this.orders = orders;
        this.carts = carts;
        this.variants = variants;
        this.users = users;
        this.addresses = addresses;
        this.pricing = pricing;
        this.library = library;
        this.ops = ops;
        this.razorpay = razorpay;
        this.razorpayProps = razorpayProps;
        this.business = props.business();
        this.clock = clock;
    }

    private record Purchase(BookVariant variant, int quantity) {
    }

    @Transactional
    public CheckoutResponse createOrder(AuthUser caller, CheckoutRequest req, String idempotencyHeader) {
        CustomerOnly.require(caller);
        User user = users.findById(caller.id())
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHORIZED", "Please log in to continue."));

        // 1. Same Idempotency-Key again (double click, network retry) → the same order, nothing new reserved
        String idempotencyKey = idempotencyKey(caller, idempotencyHeader);
        if (idempotencyKey != null) {
            var existing = orders.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return response(existing.get(), user);
            }
        }

        // 2. What is being bought, checked against live data
        List<Purchase> purchases = resolvePurchases(caller, req);
        validate(purchases, library.ownedVariantIds(caller.id()));
        boolean hasPhysical = purchases.stream().anyMatch(p -> p.variant().getFormat().isPhysical());
        boolean hasEbook = purchases.stream().anyMatch(p -> p.variant().getFormat() == BookFormat.EBOOK);

        // 3. Price on the server
        PricingService.Summary summary = pricing.summarize(purchases.stream()
                .map(p -> new PricingService.PricedLine(p.variant().getFormat(), p.variant().getMrp(),
                        p.variant().getSellingPrice(), p.quantity()))
                .toList());
        boolean cod = req.paymentMethod() == PaymentMethod.COD;
        BigDecimal codFee = cod ? business.codFee() : BigDecimal.ZERO;
        BigDecimal total = summary.total().add(codFee);
        if (cod) {
            checkCashOnDelivery(user, hasEbook, total);
        }

        // 4. Address snapshot (physical books only)
        Address address = null;
        if (hasPhysical) {
            if (req.addressId() == null) {
                throw ApiException.badRequest("ADDRESS_REQUIRED", "Choose a delivery address.");
            }
            address = addresses.ownedBy(caller.id(), req.addressId());
        }

        // 5. The order
        PurchaseOrder order = new PurchaseOrder();
        order.setOrderNumber("TMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        order.setUserId(caller.id());
        order.setPaymentMethod(req.paymentMethod());
        order.setStatus(cod ? OrderStatus.CONFIRMED : OrderStatus.PENDING_PAYMENT);
        order.setPaymentStatus(cod ? PaymentStatus.COD_PENDING : PaymentStatus.PENDING);
        order.setSubtotal(summary.mrpTotal());
        order.setDiscount(summary.discount());
        order.setDeliveryFee(summary.deliveryFee());
        order.setCodFee(codFee);
        order.setTotal(total);
        order.setIdempotencyKey(idempotencyKey);
        if (address != null) {
            order.setShipping(address.getFullName(), address.getPhone(), address.getLine1(), address.getLine2(),
                    address.getCity(), address.getState(), address.getPincode());
        }
        for (Purchase p : purchases) {
            order.getItems().add(new OrderItem(order, p.variant(), p.quantity()));
        }
        // 6. Stock first (see reserveForNewOrder for why the order matters)
        ops.reserveForNewOrder(order.getItems());
        try {
            order = orders.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("DUPLICATE_REQUEST", "This order is already being placed. Refresh to see it.");
        }
        order.setOrderNumber("BM-" + NUMBER_DATE.format(clock.instant()) + "-" + String.format("%06d", order.getId()));
        orders.flush();

        // Stock is held for online payment and sold outright for COD
        ops.recordStockTaken(order, cod ? InventoryMovement.Reason.SALE : InventoryMovement.Reason.RESERVATION);

        if (cod) {
            ops.fulfil(order);
        } else {
            order.setReservedUntil(clock.instant().plus(Duration.ofMinutes(business.reservationMinutes())));
            order.setRazorpayOrderId(createRazorpayOrder(order));
        }
        log.info("Order {} created: {} {} total {}", order.getOrderNumber(), order.getPaymentMethod(),
                order.getStatus(), order.getTotal());
        return response(order, user);
    }

    /** Re-open payment for an order still inside its reservation window. */
    @Transactional(readOnly = true)
    public CheckoutResponse retry(AuthUser caller, String orderNumber) {
        CustomerOnly.require(caller);
        PurchaseOrder order = orders.findByOrderNumberAndUserId(orderNumber, caller.id())
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Order not found."));
        boolean payable = order.getStatus() == OrderStatus.PENDING_PAYMENT
                && order.getReservedUntil() != null && order.getReservedUntil().isAfter(clock.instant());
        if (!payable) {
            throw ApiException.conflict("ORDER_NOT_PAYABLE", "This order can no longer be paid. Please place it again.");
        }
        return response(order, users.findById(caller.id()).orElseThrow());
    }

    // ------------------------------------------------------------------

    private List<Purchase> resolvePurchases(AuthUser caller, CheckoutRequest req) {
        if (req.source() == Source.BUY_NOW) {
            if (req.variantId() == null) {
                throw ApiException.badRequest("VARIANT_REQUIRED", "Choose a format to buy.");
            }
            BookVariant v = variants.findById(req.variantId())
                    .orElseThrow(() -> ApiException.notFound("VARIANT_NOT_FOUND", "That book format doesn't exist."));
            int qty = v.getFormat() == BookFormat.EBOOK ? 1 : (req.quantity() == null ? 1 : req.quantity());
            return List.of(new Purchase(v, qty));
        }
        Cart cart = carts.findByUserId(caller.id())
                .filter(c -> !c.getItems().isEmpty())
                .orElseThrow(() -> ApiException.badRequest("STACK_EMPTY", "Your Stack is empty."));
        List<Purchase> purchases = new ArrayList<>();
        cart.getItems().forEach(i -> purchases.add(new Purchase(i.getVariant(), i.getQuantity())));
        return purchases;
    }

    /** Same rules as Your Stack; anything that would show a red note there blocks checkout. */
    private static void validate(List<Purchase> purchases, Set<Long> owned) {
        for (Purchase p : purchases) {
            BookVariant v = p.variant();
            String title = "\"" + v.getBook().getTitle() + "\"";
            if (!v.isActive() || !v.getBook().isActive()) {
                throw ApiException.conflict("UNAVAILABLE", title + " is no longer available. Remove it to continue.");
            }
            if (v.getFormat() == BookFormat.EBOOK) {
                if (owned.contains(v.getId())) {
                    throw ApiException.conflict("ALREADY_OWNED", title + " is already in your library.");
                }
                continue;
            }
            int stock = v.getStock() == null ? 0 : v.getStock();
            if (v.stockStatus() == StockStatus.OUT_OF_STOCK) {
                throw ApiException.conflict("OUT_OF_STOCK", title + " is out of stock.");
            }
            if (p.quantity() > CartService.MAX_PER_ITEM || p.quantity() > stock) {
                throw ApiException.conflict("OUT_OF_STOCK", "Only " + Math.min(stock, CartService.MAX_PER_ITEM)
                        + " copies of " + title + " are available.");
            }
        }
    }

    private void checkCashOnDelivery(User user, boolean hasEbook, BigDecimal total) {
        if (hasEbook) {
            throw ApiException.badRequest("COD_NOT_ALLOWED", "Cash on Delivery isn't available for e-books. Pay online instead.");
        }
        if (total.compareTo(business.codMaxOrderValue()) > 0) {
            throw ApiException.badRequest("COD_NOT_ALLOWED", "Cash on Delivery is available for orders up to ₹"
                    + business.codMaxOrderValue().stripTrailingZeros().toPlainString() + ".");
        }
        if (!user.isEmailVerified()) {
            throw ApiException.badRequest("EMAIL_NOT_VERIFIED", "Verify your email to use Cash on Delivery.");
        }
        long unpaid = orders.countByUserIdAndPaymentMethodAndPaymentStatusAndStatusNot(user.getId(),
                PaymentMethod.COD, PaymentStatus.COD_PENDING, OrderStatus.CANCELLED);
        if (unpaid >= business.maxUnpaidCodOrders()) {
            throw ApiException.badRequest("COD_LIMIT", "You already have " + unpaid
                    + " Cash on Delivery orders on the way. Pay online for this one.");
        }
    }

    private String createRazorpayOrder(PurchaseOrder order) {
        if (!razorpayProps.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENTS_UNAVAILABLE",
                    "Online payment isn't available right now. Try Cash on Delivery or come back later.");
        }
        try {
            return razorpay.createOrder(Money.toPaise(order.getTotal()), order.getOrderNumber());
        } catch (RazorpayGateway.GatewayException e) {
            log.error("Razorpay order creation failed for {}", order.getOrderNumber(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_GATEWAY_ERROR",
                    "We couldn't reach the payment service. Nothing was charged; please try again.");
        }
    }

    private static String idempotencyKey(AuthUser caller, String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String key = header.trim();
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be 8-40 letters, digits, - or _.");
        }
        return caller.id() + ":" + key;
    }

    private CheckoutResponse response(PurchaseOrder order, User user) {
        boolean awaitingPayment = order.getStatus() == OrderStatus.PENDING_PAYMENT;
        return new CheckoutResponse(
                order.getOrderNumber(),
                order.getStatus(),
                order.getPaymentMethod(),
                order.getTotal(),
                awaitingPayment ? Money.toPaise(order.getTotal()) : null,
                "INR",
                awaitingPayment ? order.getRazorpayOrderId() : null,
                awaitingPayment ? razorpayProps.keyId() : null,
                awaitingPayment ? order.getReservedUntil() : null,
                new Prefill(user.getFullName(), user.getEmail(), user.getPhone()));
    }
}
