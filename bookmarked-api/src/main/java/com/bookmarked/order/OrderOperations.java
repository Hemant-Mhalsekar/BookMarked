package com.bookmarked.order;

import com.bookmarked.cart.CartRepository;
import com.bookmarked.catalog.BookFormat;
import com.bookmarked.catalog.BookVariantRepository;
import com.bookmarked.catalog.InventoryMovement;
import com.bookmarked.catalog.InventoryMovementRepository;
import com.bookmarked.common.ApiException;
import com.bookmarked.config.AppProperties;
import com.bookmarked.mail.MailService;
import com.bookmarked.notification.NotificationService;
import com.bookmarked.notification.NotificationService.Type;
import com.bookmarked.order.OrderEnums.FulfillmentStatus;
import com.bookmarked.user.User;
import com.bookmarked.user.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Building blocks shared by checkout, payment confirmation and expiry: moving
 * stock (always with a ledger entry), fulfilling a paid order, and the status
 * history. Callers provide the transaction.
 */
@Component
public class OrderOperations {

    private final BookVariantRepository variants;
    private final InventoryMovementRepository movements;
    private final CartRepository carts;
    private final UserRepository users;
    private final MailService mail;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int lowStockThreshold;

    public OrderOperations(BookVariantRepository variants, InventoryMovementRepository movements, CartRepository carts,
                           UserRepository users, MailService mail, NotificationService notifications,
                           JdbcTemplate jdbc, Clock clock, AppProperties props) {
        this.variants = variants;
        this.movements = movements;
        this.carts = carts;
        this.users = users;
        this.mail = mail;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
        this.lowStockThreshold = props.business().lowStockThreshold();
    }

    // ------------------------------------------------------------------
    // Stock
    // ------------------------------------------------------------------

    /**
     * Takes stock for every physical item, or throws 409 OUT_OF_STOCK. The caller's
     * transaction rolls back whatever was already taken.
     */
    /**
     * Takes stock for a new order <b>before</b> the order row is inserted.
     * Inserting order_items puts a shared (foreign-key) lock on each book_variants
     * row; if that came first, concurrent buyers would each hold a shared lock and
     * then wait for an exclusive one, and MySQL would kill some of them as
     * deadlocks. Taking the exclusive lock first makes buyers queue instead, and
     * going in variant-id order keeps two multi-book orders from deadlocking.
     */
    public void reserveForNewOrder(List<OrderItem> items) {
        List<OrderItem> sorted = items.stream().filter(i -> i.getFormat().isPhysical())
                .sorted(java.util.Comparator.comparing(i -> i.getVariant().getId())).toList();
        for (OrderItem item : sorted) {
            if (variants.reserveStock(item.getVariant().getId(), item.getQuantity()) != 1) {
                throw ApiException.conflict("OUT_OF_STOCK", "Sorry, \"" + item.getTitleSnapshot() + "\" ("
                        + item.getFormat().name().toLowerCase(Locale.ROOT) + ") just ran out in that quantity.");
            }
        }
    }

    /** Writes the inventory ledger for stock already taken by {@link #reserveForNewOrder}. */
    public void recordStockTaken(PurchaseOrder order, InventoryMovement.Reason reason) {
        for (OrderItem item : physical(order)) {
            movements.save(new InventoryMovement(item.getVariant().getId(), -item.getQuantity(), reason,
                    order.getOrderNumber(), null, null));
            alertIfLowStock(item);
        }
    }

    /** Tells inventory staff once, when a sale takes stock from above the threshold to at or below it. */
    private void alertIfLowStock(OrderItem item) {
        Integer left = jdbc.queryForObject("select stock from book_variants where id = ?", Integer.class,
                item.getVariant().getId());
        if (left != null && left <= lowStockThreshold && left + item.getQuantity() > lowStockThreshold) {
            String label = item.getTitleSnapshot() + " (" + formatLabel(item.getFormat()) + ")";
            notifications.notifyStaff("INVENTORY_UPDATE", Type.LOW_STOCK,
                    left == 0 ? "Sold out: " + label : "Low stock: " + label,
                    left == 0 ? label + " is out of stock." : "Only " + left + " left of " + label + ".",
                    "/admin/inventory?filter=LOW");
        }
    }

    /** Like {@link #reserveForNewOrder} but for an existing order, all-or-nothing without throwing (used for late payments). */
    public boolean tryRetakeStock(PurchaseOrder order) {
        List<OrderItem> taken = new ArrayList<>();
        for (OrderItem item : physical(order)) {
            if (variants.reserveStock(item.getVariant().getId(), item.getQuantity()) == 1) {
                taken.add(item);
            } else {
                taken.forEach(t -> variants.releaseStock(t.getVariant().getId(), t.getQuantity()));
                return false;
            }
        }
        taken.forEach(t -> movements.save(new InventoryMovement(t.getVariant().getId(), -t.getQuantity(),
                InventoryMovement.Reason.RESERVATION, order.getOrderNumber(), "Re-reserved for late payment", null)));
        return true;
    }

    /** Puts the stock of these (physical) items back, with a ledger entry each. */
    public void releaseItems(PurchaseOrder order, List<OrderItem> items, InventoryMovement.Reason reason, String note,
                             Long actorId) {
        for (OrderItem item : items) {
            if (!item.getFormat().isPhysical()) {
                continue;
            }
            variants.releaseStock(item.getVariant().getId(), item.getQuantity());
            movements.save(new InventoryMovement(item.getVariant().getId(), item.getQuantity(), reason,
                    order.getOrderNumber(), note, actorId));
        }
    }

    public void releaseStock(PurchaseOrder order, InventoryMovement.Reason reason, String note) {
        for (OrderItem item : physical(order)) {
            variants.releaseStock(item.getVariant().getId(), item.getQuantity());
            movements.save(new InventoryMovement(item.getVariant().getId(), item.getQuantity(), reason,
                    order.getOrderNumber(), note, null));
        }
    }

    // ------------------------------------------------------------------
    // Fulfilment
    // ------------------------------------------------------------------

    /**
     * Runs once an order is confirmed (paid online, or placed as COD): e-books go to
     * My Library and are delivered instantly, physical items are PLACED, the bought
     * formats leave Your Stack, and a confirmation email goes out.
     */
    public void fulfil(PurchaseOrder order) {
        Instant now = clock.instant();
        boolean hasEbooks = false;
        boolean hasPhysical = false;
        for (OrderItem item : order.getItems()) {
            if (item.getFormat() == BookFormat.EBOOK) {
                hasEbooks = true;
                item.setFulfillmentStatus(FulfillmentStatus.DELIVERED);
                item.setDeliveredAt(now);
                jdbc.update("insert ignore into user_library (user_id, variant_id, order_id, purchased_at) values (?, ?, ?, ?)",
                        order.getUserId(), item.getVariant().getId(), order.getId(), Timestamp.from(now));
                history(item, FulfillmentStatus.DELIVERED, "Added to My Library");
            } else {
                hasPhysical = true;
                item.setFulfillmentStatus(FulfillmentStatus.PLACED);
                history(item, FulfillmentStatus.PLACED, "Order placed");
            }
        }
        removeFromStack(order);
        sendConfirmation(order, hasEbooks, hasPhysical);
        String link = "/orders/" + order.getOrderNumber();
        notifications.notify(order.getUserId(), Type.ORDER_PLACED, "Order " + order.getOrderNumber() + " confirmed",
                hasEbooks && !hasPhysical ? "Your e-books are ready in My Library." : "We'll let you know when it ships.",
                link);
        if (hasPhysical) {
            notifications.notifyStaff("ORDER_UPDATE", Type.NEW_ORDER, "New order " + order.getOrderNumber(),
                    rupees(order.getTotal()) + " · " + (order.getPaymentMethod() == OrderEnums.PaymentMethod.COD
                            ? "Cash on Delivery" : "Paid online") + " · ready to pack",
                    "/admin/orders/" + order.getOrderNumber());
        }
        refreshOrderStatus(order);   // an e-book-only order is complete straight away
    }

    public void cancelItems(PurchaseOrder order, String note) {
        for (OrderItem item : order.getItems()) {
            item.setFulfillmentStatus(FulfillmentStatus.CANCELLED);
            history(item, FulfillmentStatus.CANCELLED, note);
        }
    }

    public void history(OrderItem item, FulfillmentStatus status, String note) {
        history(item, status, note, null);
    }

    /** @param actorId the staff member who made the change, or null for the customer / the system */
    public void history(OrderItem item, FulfillmentStatus status, String note, Long actorId) {
        if (item.getId() == null) {
            return;
        }
        jdbc.update("insert into order_status_history (order_item_id, status, note, changed_by, changed_at) values (?, ?, ?, ?, ?)",
                item.getId(), status.name(), note, actorId, Timestamp.from(clock.instant()));
    }

    /**
     * Keeps the order-level status in line with its items: all cancelled → CANCELLED;
     * every item finished (delivered, cancelled or returned) → COMPLETED.
     */
    public void refreshOrderStatus(PurchaseOrder order) {
        if (order.getStatus() != OrderEnums.OrderStatus.CONFIRMED && order.getStatus() != OrderEnums.OrderStatus.COMPLETED) {
            return;
        }
        boolean allCancelled = order.getItems().stream()
                .allMatch(i -> i.getFulfillmentStatus() == FulfillmentStatus.CANCELLED);
        boolean allDone = order.getItems().stream().allMatch(i -> switch (i.getFulfillmentStatus()) {
            case DELIVERED, CANCELLED, RETURNED -> true;
            default -> false;
        });
        if (allCancelled) {
            order.setStatus(OrderEnums.OrderStatus.CANCELLED);
        } else if (allDone) {
            order.setStatus(OrderEnums.OrderStatus.COMPLETED);
        } else {
            order.setStatus(OrderEnums.OrderStatus.CONFIRMED);
        }
    }

    static String formatName(BookFormat format) {
        return formatLabel(format);
    }

    private void removeFromStack(PurchaseOrder order) {
        Set<Long> bought = order.getItems().stream().map(i -> i.getVariant().getId()).collect(Collectors.toSet());
        carts.findByUserId(order.getUserId()).ifPresent(cart -> {
            if (cart.getItems().removeIf(i -> bought.contains(i.getVariant().getId()))) {
                cart.touch();
                carts.flush();
            }
        });
    }

    private void sendConfirmation(PurchaseOrder order, boolean hasEbooks, boolean hasPhysical) {
        User user = users.findById(order.getUserId()).orElse(null);
        if (user == null) {
            return;
        }
        List<MailService.OrderLine> lines = order.getItems().stream()
                .map(i -> new MailService.OrderLine(i.getTitleSnapshot(),
                        formatLabel(i.getFormat()) + " × " + i.getQuantity(), rupees(i.lineTotal())))
                .toList();
        mail.sendOrderConfirmation(new MailService.Recipient(user.getEmail(), user.getFullName()),
                order.getOrderNumber(), lines, rupees(order.getTotal()),
                order.getPaymentMethod() == OrderEnums.PaymentMethod.COD, hasEbooks, hasPhysical);
    }

    private static List<OrderItem> physical(PurchaseOrder order) {
        return order.getItems().stream().filter(i -> i.getFormat().isPhysical()).toList();
    }

    private static String formatLabel(BookFormat format) {
        return switch (format) {
            case PAPERBACK -> "Paperback";
            case HARDCOVER -> "Hardcover";
            case EBOOK -> "E-book";
        };
    }

    static String rupees(BigDecimal amount) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"));
        f.setMaximumFractionDigits(amount.stripTrailingZeros().scale() > 0 ? 2 : 0);
        return f.format(amount);
    }
}
