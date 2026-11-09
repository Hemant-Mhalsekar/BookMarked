package com.bookmarked.cart;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.bookmark.BookmarkService;
import com.bookmarked.cart.CartDtos.CartLine;
import com.bookmarked.cart.CartDtos.CartView;
import com.bookmarked.cart.CartDtos.GuestItem;
import com.bookmarked.cart.CartDtos.LineIssue;
import com.bookmarked.catalog.Book;
import com.bookmarked.catalog.BookFormat;
import com.bookmarked.catalog.BookVariant;
import com.bookmarked.catalog.BookVariantRepository;
import com.bookmarked.catalog.StockStatus;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.CustomerOnly;
import com.bookmarked.library.LibraryLookup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Your Stack (the cart). Rules:
 * <ul>
 *   <li>Prices are always read live from the variant; nothing the client sends is trusted.</li>
 *   <li>E-books: quantity is always 1, and an e-book already in My Library can't be added.</li>
 *   <li>Physical books: at most {@value #MAX_PER_ITEM} per line, capped at current stock.</li>
 *   <li>Lines that can't be bought (hidden, out of stock, too many) stay visible but are not
 *       charged and block checkout.</li>
 * </ul>
 */
@Service
public class CartService {

    public static final int MAX_PER_ITEM = 10;

    private final CartRepository carts;
    private final BookVariantRepository variants;
    private final PricingService pricing;
    private final LibraryLookup library;
    private final BookmarkService bookmarks;

    public CartService(CartRepository carts, BookVariantRepository variants, PricingService pricing,
                       LibraryLookup library, BookmarkService bookmarks) {
        this.carts = carts;
        this.variants = variants;
        this.pricing = pricing;
        this.library = library;
        this.bookmarks = bookmarks;
    }

    // ------------------------------------------------------------------
    // Logged-in customer
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public CartView view(AuthUser caller) {
        CustomerOnly.require(caller);
        return carts.findByUserId(caller.id())
                .map(cart -> render(cart, null))
                .orElseGet(() -> build(List.of(), Set.of(), null));
    }

    @Transactional
    public CartView add(AuthUser caller, Long variantId, Integer requestedQty) {
        CustomerOnly.require(caller);
        int qty = requestedQty == null ? 1 : requestedQty;
        BookVariant variant = purchasableVariant(variantId);
        Set<Long> owned = library.ownedVariantIds(caller.id());
        if (variant.getFormat() == BookFormat.EBOOK && owned.contains(variant.getId())) {
            throw ApiException.conflict("ALREADY_OWNED", "This e-book is already in your library.");
        }
        if (variant.stockStatus() == StockStatus.OUT_OF_STOCK) {
            throw ApiException.conflict("OUT_OF_STOCK", "Sorry, this format is out of stock.");
        }

        Cart cart = cartFor(caller.id());
        CartItem item = cart.itemForVariant(variantId).orElse(null);
        int wanted = (item == null ? 0 : item.getQuantity()) + qty;
        int allowed = maxQuantity(variant);
        int finalQty = Math.min(wanted, allowed);
        String notice = noticeFor(variant, wanted, finalQty);

        if (item == null) {
            cart.getItems().add(new CartItem(cart, variant, finalQty));
        } else {
            item.setQuantity(finalQty);
            item.setPriceAtAdd(variant.getSellingPrice());
        }
        cart.touch();
        carts.flush();   // cart is managed: new items are persisted by cascade, ids assigned
        return render(cart, notice);
    }

    @Transactional
    public CartView setQuantity(AuthUser caller, Long itemId, int requestedQty) {
        CustomerOnly.require(caller);
        Cart cart = existingCart(caller.id());
        CartItem item = cart.item(itemId).orElseThrow(CartService::itemNotFound);
        BookVariant variant = item.getVariant();
        int allowed = Math.max(1, maxQuantity(variant));
        int finalQty = Math.min(requestedQty, allowed);
        item.setQuantity(finalQty);
        cart.touch();
        return render(cart, noticeFor(variant, requestedQty, finalQty));
    }

    @Transactional
    public CartView remove(AuthUser caller, Long itemId) {
        CustomerOnly.require(caller);
        Cart cart = existingCart(caller.id());
        CartItem item = cart.item(itemId).orElseThrow(CartService::itemNotFound);
        cart.getItems().remove(item);
        cart.touch();
        carts.flush();
        return render(cart, null);
    }

    /** Moves the line's book to Bookmarks and takes it out of the Stack. */
    @Transactional
    public CartView saveForLater(AuthUser caller, Long itemId) {
        CustomerOnly.require(caller);
        Cart cart = existingCart(caller.id());
        CartItem item = cart.item(itemId).orElseThrow(CartService::itemNotFound);
        bookmarks.addFor(caller.id(), item.getVariant().getBook().getId());
        cart.getItems().remove(item);
        cart.touch();
        carts.flush();
        return render(cart, "Moved to your Bookmarks.");
    }

    /** The customer has seen the "price changed" notice; stop showing it. */
    @Transactional
    public CartView acceptPrices(AuthUser caller) {
        CustomerOnly.require(caller);
        Cart cart = existingCart(caller.id());
        cart.getItems().forEach(i -> i.setPriceAtAdd(i.getVariant().getSellingPrice()));
        return render(cart, null);
    }

    /**
     * Folds a guest Stack into the account after login. Quantities for the same
     * format are added together and capped; lines that can't be bought are dropped.
     */
    @Transactional
    public CartView merge(AuthUser caller, List<GuestItem> guestItems) {
        CustomerOnly.require(caller);
        Set<Long> owned = library.ownedVariantIds(caller.id());
        Map<Long, BookVariant> byId = loadVariants(guestItems);
        Cart cart = cartFor(caller.id());
        boolean adjusted = false;

        for (GuestItem guest : guestItems) {
            BookVariant v = byId.get(guest.variantId());
            if (v == null || !isBuyable(v) || v.stockStatus() == StockStatus.OUT_OF_STOCK
                    || (v.getFormat() == BookFormat.EBOOK && owned.contains(v.getId()))) {
                adjusted = true;
                continue;
            }
            CartItem item = cart.itemForVariant(v.getId()).orElse(null);
            int wanted = (item == null ? 0 : item.getQuantity()) + guest.quantity();
            int finalQty = Math.min(wanted, maxQuantity(v));
            adjusted |= finalQty != wanted && v.getFormat().isPhysical();
            if (item == null) {
                cart.getItems().add(new CartItem(cart, v, finalQty));
            } else {
                item.setQuantity(finalQty);
            }
        }
        cart.touch();
        carts.flush();   // cart is managed: new items are persisted by cascade, ids assigned
        return render(cart, adjusted ? "We merged your Stack. Some items were adjusted to match availability." : null);
    }

    // ------------------------------------------------------------------
    // Guest
    // ------------------------------------------------------------------

    /** Prices a guest Stack held in the browser. Nothing is stored. */
    @Transactional(readOnly = true)
    public CartView preview(List<GuestItem> guestItems) {
        Map<Long, BookVariant> byId = loadVariants(guestItems);
        List<LineInput> inputs = new ArrayList<>();
        Map<Long, Integer> merged = new LinkedHashMap<>();
        guestItems.forEach(g -> merged.merge(g.variantId(), g.quantity(), Integer::sum));
        merged.forEach((variantId, qty) -> {
            BookVariant v = byId.get(variantId);
            if (v != null) {
                int q = v.getFormat() == BookFormat.EBOOK ? 1 : Math.min(qty, MAX_PER_ITEM);
                inputs.add(new LineInput(null, v, q, null));
            }
        });
        return build(inputs, Set.of(), null);
    }

    // ------------------------------------------------------------------
    // Building the view
    // ------------------------------------------------------------------

    private record LineInput(Long itemId, BookVariant variant, int quantity, BigDecimal priceAtAdd) {
    }

    private CartView render(Cart cart, String notice) {
        List<LineInput> inputs = cart.getItems().stream()
                .map(i -> new LineInput(i.getId(), i.getVariant(), i.getQuantity(), i.getPriceAtAdd()))
                .toList();
        return build(inputs, library.ownedVariantIds(cart.getUserId()), notice);
    }

    private CartView build(List<LineInput> inputs, Set<Long> owned, String notice) {
        List<CartLine> lines = new ArrayList<>();
        List<PricingService.PricedLine> priced = new ArrayList<>();
        int totalQuantity = 0;
        boolean pricesChanged = false;

        for (LineInput in : inputs) {
            BookVariant v = in.variant();
            Book book = v.getBook();
            StockStatus status = v.stockStatus();
            int max = maxQuantity(v);

            LineIssue issue = null;
            if (!isBuyable(v)) {
                issue = LineIssue.UNAVAILABLE;
            } else if (v.getFormat() == BookFormat.EBOOK && owned.contains(v.getId())) {
                issue = LineIssue.ALREADY_OWNED;
            } else if (status == StockStatus.OUT_OF_STOCK) {
                issue = LineIssue.OUT_OF_STOCK;
            } else if (in.quantity() > max) {
                issue = LineIssue.QUANTITY_EXCEEDS_STOCK;
            }

            BigDecimal previous = in.priceAtAdd() != null && in.priceAtAdd().compareTo(v.getSellingPrice()) != 0
                    ? in.priceAtAdd() : null;
            pricesChanged |= previous != null && issue == null;
            BigDecimal lineTotal = v.getSellingPrice().multiply(BigDecimal.valueOf(in.quantity()));

            lines.add(new CartLine(in.itemId(), v.getId(), book.getId(), book.getSlug(), book.getTitle(),
                    book.authorNames(), book.getCoverImageUrl(), v.getFormat(), v.getMrp(), v.getSellingPrice(),
                    previous, in.quantity(), Math.max(max, 0), lineTotal, status,
                    status == StockStatus.LOW_STOCK ? v.getStock() : null, issue));
            totalQuantity += in.quantity();
            if (issue == null) {
                priced.add(new PricingService.PricedLine(v.getFormat(), v.getMrp(), v.getSellingPrice(), in.quantity()));
            }
        }

        boolean blocked = priced.isEmpty() || lines.stream().anyMatch(l -> l.issue() != null);
        return new CartView(lines, pricing.summarize(priced), totalQuantity, blocked, pricesChanged, notice);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** E-books: 1. Physical: up to {@link #MAX_PER_ITEM}, never more than in stock (0 when sold out). */
    static int maxQuantity(BookVariant v) {
        if (v.getFormat() == BookFormat.EBOOK) {
            return 1;
        }
        int stock = v.getStock() == null ? 0 : v.getStock();
        return Math.max(0, Math.min(MAX_PER_ITEM, stock));
    }

    private static boolean isBuyable(BookVariant v) {
        return v.isActive() && v.getBook().isActive();
    }

    private static String noticeFor(BookVariant v, int wanted, int finalQty) {
        if (finalQty >= wanted) {
            return null;
        }
        if (v.getFormat() == BookFormat.EBOOK) {
            return "E-books are limited to one copy.";
        }
        int stock = v.getStock() == null ? 0 : v.getStock();
        return stock < MAX_PER_ITEM
                ? "Only " + stock + " available, so we've set the quantity to " + finalQty + "."
                : "You can buy up to " + MAX_PER_ITEM + " copies of a book.";
    }

    private BookVariant purchasableVariant(Long variantId) {
        BookVariant v = variants.findById(variantId)
                .orElseThrow(() -> ApiException.notFound("VARIANT_NOT_FOUND", "That book format doesn't exist."));
        if (!isBuyable(v)) {
            throw ApiException.conflict("UNAVAILABLE", "This book is no longer available.");
        }
        return v;
    }

    private Map<Long, BookVariant> loadVariants(List<GuestItem> items) {
        List<Long> ids = items.stream().map(GuestItem::variantId).distinct().toList();
        return variants.findAllById(ids).stream().collect(Collectors.toMap(BookVariant::getId, Function.identity()));
    }

    private Cart cartFor(Long userId) {
        return carts.findByUserId(userId).orElseGet(() -> carts.saveAndFlush(new Cart(userId)));
    }

    private Cart existingCart(Long userId) {
        return carts.findByUserId(userId).orElseThrow(CartService::itemNotFound);
    }

    private static ApiException itemNotFound() {
        return ApiException.notFound("ITEM_NOT_FOUND", "That item isn't in your Stack.");
    }
}
