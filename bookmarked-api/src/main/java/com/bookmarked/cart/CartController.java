package com.bookmarked.cart;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.cart.CartDtos.AddItemRequest;
import com.bookmarked.cart.CartDtos.CartView;
import com.bookmarked.cart.CartDtos.GuestItemsRequest;
import com.bookmarked.cart.CartDtos.QuantityRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cart")
@Tag(name = "Your Stack", description = "The shopping cart. Every response is the full, freshly priced Stack.")
public class CartController {

    private final CartService cart;

    public CartController(CartService cart) {
        this.cart = cart;
    }

    @Operation(summary = "Price a guest Stack kept in the browser (no login, nothing stored)")
    @PostMapping("/preview")
    public CartView preview(@Valid @RequestBody GuestItemsRequest req) {
        return cart.preview(req.items());
    }

    @Operation(summary = "The logged-in customer's Stack", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public CartView view(@AuthenticationPrincipal AuthUser caller) {
        return cart.view(caller);
    }

    @Operation(summary = "Add a format of a book (adds to the quantity if already there)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/items")
    public CartView add(@AuthenticationPrincipal AuthUser caller, @Valid @RequestBody AddItemRequest req) {
        return cart.add(caller, req.variantId(), req.quantity());
    }

    @Operation(summary = "Change a line's quantity", security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping("/items/{itemId}")
    public CartView setQuantity(@AuthenticationPrincipal AuthUser caller, @PathVariable Long itemId,
                                @Valid @RequestBody QuantityRequest req) {
        return cart.setQuantity(caller, itemId, req.quantity());
    }

    @Operation(summary = "Remove a line", security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/items/{itemId}")
    public CartView remove(@AuthenticationPrincipal AuthUser caller, @PathVariable Long itemId) {
        return cart.remove(caller, itemId);
    }

    @Operation(summary = "Move a line to Bookmarks", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/items/{itemId}/save-for-later")
    public CartView saveForLater(@AuthenticationPrincipal AuthUser caller, @PathVariable Long itemId) {
        return cart.saveForLater(caller, itemId);
    }

    @Operation(summary = "Acknowledge price changes", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/accept-prices")
    public CartView acceptPrices(@AuthenticationPrincipal AuthUser caller) {
        return cart.acceptPrices(caller);
    }

    @Operation(summary = "Merge the guest Stack into the account after login",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/merge")
    public CartView merge(@AuthenticationPrincipal AuthUser caller, @Valid @RequestBody GuestItemsRequest req) {
        return cart.merge(caller, req.items());
    }
}
