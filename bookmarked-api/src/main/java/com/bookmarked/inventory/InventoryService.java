package com.bookmarked.inventory;

import com.bookmarked.audit.AuditService;
import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.PageResponse;
import com.bookmarked.config.AppProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stock of printed books. Every change goes through one atomic UPDATE that can't
 * take stock below zero (also enforced by a CHECK constraint), and is written to
 * the inventory ledger with who did it and why.
 * <p>
 * "Reserved" is stock already taken by orders still waiting for payment; it comes
 * back if the payment window lapses. "Sold" counts items in paid or COD orders
 * from the last 30 days, minus cancelled items.
 */
@Service
public class InventoryService {

    public static final int MAX_CHANGE = 10_000;
    public static final int MAX_STOCK = 100_000;

    public record StockRow(Long variantId, Long bookId, String title, String slug, String format, String isbn,
                           int stock, int reserved, int sold30, boolean active, boolean lowStock) {
    }

    public record InventoryPage(PageResponse<StockRow> rows, int lowCount, int outCount, int threshold) {
    }

    public record AdjustRequest(@NotBlank String reason,
                                @Min(-MAX_CHANGE) @Max(MAX_CHANGE) int quantity,
                                @Size(max = 200) String note) {
    }

    public record Movement(Long id, int change, String reason, String reference, String note, String by,
                           Instant at) {
    }

    private static final String ROW = """
            select v.id, b.id, b.title, b.slug, v.format, v.isbn, v.stock,
                   (select coalesce(sum(oi.quantity), 0) from order_items oi join orders o on o.id = oi.order_id
                    where oi.variant_id = v.id and o.status = 'PENDING_PAYMENT'),
                   (select coalesce(sum(oi.quantity), 0) from order_items oi join orders o on o.id = oi.order_id
                    where oi.variant_id = v.id and o.status in ('CONFIRMED', 'COMPLETED')
                      and oi.fulfillment_status <> 'CANCELLED' and o.created_at >= ?),
                   v.active and b.active
            from book_variants v join books b on b.id = v.book_id
            """;

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;
    private final int threshold;

    public InventoryService(JdbcTemplate jdbc, AuditService audit, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.threshold = props.business().lowStockThreshold();
    }

    /** filter: ALL, LOW (1..threshold left) or OUT (none left). Low and sold-out rows come first. */
    @Transactional(readOnly = true)
    public InventoryPage list(String q, String filter, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be >= 0 and size between 1 and 100");
        }
        StringBuilder where = new StringBuilder(" where v.stock is not null");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String term = "%" + q.strip().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%")
                    .replace("_", "!_") + "%";
            where.append(" and (lower(b.title) like ? escape '!' or v.isbn like ? escape '!')");
            args.add(term);
            args.add(term);
        }
        switch (filter == null ? "ALL" : filter.toUpperCase(Locale.ROOT)) {
            case "LOW" -> where.append(" and v.stock > 0 and v.stock <= ").append(threshold);
            case "OUT" -> where.append(" and v.stock = 0");
            case "ALL" -> {
            }
            default -> throw ApiException.badRequest("INVALID_FILTER", "Use ALL, LOW or OUT.");
        }
        long total = jdbc.queryForObject("select count(*) from book_variants v join books b on b.id = v.book_id" + where,
                Long.class, args.toArray());
        List<Object> rowArgs = new ArrayList<>();
        rowArgs.add(since());
        rowArgs.addAll(args);
        rowArgs.add(size);
        rowArgs.add((long) page * size);
        List<StockRow> rows = jdbc.query(ROW + where + " order by (v.stock <= " + threshold + ") desc, v.stock, b.title, v.format"
                + " limit ? offset ?", mapper(), rowArgs.toArray());
        Map<String, Object> counts = jdbc.queryForMap("""
                select coalesce(sum(stock > 0 and stock <= ?), 0) low, coalesce(sum(stock = 0), 0) out_count
                from book_variants v join books b on b.id = v.book_id
                where v.stock is not null and v.active = true and b.active = true
                """, threshold);
        return new InventoryPage(new PageResponse<>(rows, page, size, total, (int) ((total + size - 1) / size)),
                ((Number) counts.get("low")).intValue(), ((Number) counts.get("out_count")).intValue(), threshold);
    }

    /**
     * RESTOCK adds books that arrived; ADJUSTMENT corrects a count (damaged, lost,
     * miscounted) in either direction and needs a note saying why.
     */
    @Transactional
    public StockRow adjust(AuthUser staff, long variantId, AdjustRequest req, String ip) {
        String reason = req.reason() == null ? "" : req.reason().strip().toUpperCase(Locale.ROOT);
        String note = req.note() == null || req.note().isBlank() ? null : req.note().strip();
        int qty = req.quantity();
        switch (reason) {
            case "RESTOCK" -> {
                if (qty <= 0) {
                    throw ApiException.badRequest("INVALID_QUANTITY", "A restock must add at least 1 copy.");
                }
            }
            case "ADJUSTMENT" -> {
                if (qty == 0) {
                    throw ApiException.badRequest("INVALID_QUANTITY", "Enter how many copies to add or remove.");
                }
                if (note == null || note.length() < 3) {
                    throw ApiException.badRequest("NOTE_REQUIRED", "Say why the count is changing (e.g. damaged in storage).");
                }
            }
            default -> throw ApiException.badRequest("INVALID_REASON", "Reason must be RESTOCK or ADJUSTMENT.");
        }

        // lock the row so the before/after values in the ledger and audit are exact
        List<Integer> current = jdbc.queryForList("select stock from book_variants where id = ? and stock is not null for update",
                Integer.class, variantId);
        if (current.isEmpty()) {
            throw ApiException.notFound("VARIANT_NOT_FOUND", "No printed edition with that id.");
        }
        int before = current.get(0);
        if (before + qty < 0) {
            throw ApiException.conflict("STOCK_NEGATIVE",
                    "Only " + before + " in stock, so you can remove at most " + before + ".");
        }
        if (before + qty > MAX_STOCK) {
            throw ApiException.badRequest("STOCK_TOO_HIGH", "Stock can't go above " + MAX_STOCK + ".");
        }
        int changed = jdbc.update("update book_variants set stock = stock + ? where id = ? and stock + ? >= 0",
                qty, variantId, qty);
        if (changed != 1) {
            throw ApiException.conflict("STOCK_NEGATIVE", "Not enough stock to remove that many.");
        }
        Instant now = clock.instant();
        jdbc.update("""
                insert into inventory_movements (variant_id, change_qty, reason, reference_id, note, performed_by, created_at)
                values (?, ?, ?, null, ?, ?, ?)
                """, variantId, qty, reason, note, staff.id(), Timestamp.from(now));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("stock", before + qty);
        after.put("change", qty);
        after.put("reason", reason);
        if (note != null) {
            after.put("note", note);
        }
        audit.record(staff.id(), "Inventory.adjust", "INVENTORY", String.valueOf(variantId), Map.of("stock", before),
                after, ip);
        return row(variantId);
    }

    /** The ledger for one edition, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<Movement> movements(long variantId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be >= 0 and size between 1 and 100");
        }
        row(variantId);
        long total = jdbc.queryForObject("select count(*) from inventory_movements where variant_id = ?", Long.class, variantId);
        List<Movement> rows = jdbc.query("""
                select m.id, m.change_qty, m.reason, m.reference_id, m.note, u.full_name, m.created_at
                from inventory_movements m left join users u on u.id = m.performed_by
                where m.variant_id = ? order by m.created_at desc, m.id desc limit ? offset ?
                """, (rs, i) -> new Movement(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant()), variantId, size, (long) page * size);
        return new PageResponse<>(rows, page, size, total, (int) ((total + size - 1) / size));
    }

    public StockRow row(long variantId) {
        List<StockRow> rows = jdbc.query(ROW + " where v.id = ? and v.stock is not null", mapper(), since(), variantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("VARIANT_NOT_FOUND", "No printed edition with that id.");
        }
        return rows.get(0);
    }

    private Timestamp since() {
        return Timestamp.from(clock.instant().minus(Duration.ofDays(30)));
    }

    private RowMapper<StockRow> mapper() {
        return (rs, i) -> new StockRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getBoolean(10), rs.getInt(7) <= threshold);
    }
}
