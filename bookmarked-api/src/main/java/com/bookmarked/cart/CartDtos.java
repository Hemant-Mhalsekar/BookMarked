package com.bookmarked.cart;

import com.bookmarked.catalog.BookFormat;
import com.bookmarked.catalog.StockStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public final class CartDtos {

    private CartDtos() {
    }

    // ---- requests ----

    public record AddItemRequest(@NotNull Long variantId, @Min(1) @Max(CartService.MAX_PER_ITEM) Integer quantity) {
    }

    public record QuantityRequest(@NotNull @Min(1) @Max(CartService.MAX_PER_ITEM) Integer quantity) {
    }

    /** One line of a guest Stack kept in the browser. */
    public record GuestItem(@NotNull Long variantId, @NotNull @Min(1) @Max(CartService.MAX_PER_ITEM) Integer quantity) {
    }

    public record GuestItemsRequest(@NotNull @Size(max = 50) List<@Valid @NotNull GuestItem> items) {
    }

    // ---- responses ----

    /**
     * Why a line can't be bought right now. Lines with an issue are shown but not
     * charged, and they block checkout until fixed or removed.
     */
    public enum LineIssue {
        UNAVAILABLE,
        OUT_OF_STOCK,
        QUANTITY_EXCEEDS_STOCK,
        ALREADY_OWNED
    }

    public record CartLine(
            Long itemId,              // null in a guest preview
            Long variantId,
            Long bookId,
            String slug,
            String title,
            List<String> authors,
            String coverImageUrl,
            BookFormat format,
            BigDecimal mrp,
            BigDecimal unitPrice,
            BigDecimal previousPrice, // set when the price changed since the book was added
            int quantity,
            int maxQuantity,
            BigDecimal lineTotal,
            StockStatus stockStatus,
            Integer stockLeft,
            LineIssue issue) {
    }

    public record CartView(
            List<CartLine> lines,
            PricingService.Summary summary,
            int totalQuantity,        // every line, for the navbar badge
            boolean checkoutBlocked,
            boolean pricesChanged,
            String notice) {
    }
}
