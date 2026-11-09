package com.bookmarked.order;

import com.bookmarked.cart.CartRepository;
import com.bookmarked.catalog.BookFormat;
import com.bookmarked.catalog.BookVariantRepository;
import com.bookmarked.catalog.InventoryMovement;
import com.bookmarked.catalog.InventoryMovementRepository;
import com.bookmarked.common.ApiException;
import com.bookmarked.mail.MailService;
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
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OrderOperations(BookVariantRepository variants, InventoryMovementRepository movements, CartRepository carts,
                           UserRepository users, MailService mail, JdbcTemplate jdbc, Clock clock) {
        this.variants = variants;
        this.movements = movements;
        this.carts = carts;
        this.users = users;
        this.mail = mail;
        this.jdbc = jdbc;
        this.clock = clock;
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
    }

    public void cancelItems(PurchaseOrder order, String note) {
        for (OrderItem item : order.getItems()) {
            item.setFulfillmentStatus(FulfillmentStatus.CANCELLED);
            history(item, FulfillmentStatus.CANCELLED, note);
        }
    }

    public void history(OrderItem item, FulfillmentStatus status, String note) {
        if (item.getId() == null) {
            return;
        }
        jdbc.update("insert into order_status_history (order_item_id, status, note, changed_by, changed_at) values (?, ?, ?, null, ?)",
                item.getId(), status.name(), note, Timestamp.from(clock.instant()));
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
