package com.bookmarked.order;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.order.CheckoutDtos.CheckoutRequest;
import com.bookmarked.order.CheckoutDtos.CheckoutResponse;
import com.bookmarked.order.CheckoutDtos.VerifyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/checkout")
@Tag(name = "Checkout", description = "Place orders and confirm Razorpay payments")
@SecurityRequirement(name = "bearerAuth")
public class CheckoutController {

    private final CheckoutService checkout;
    private final PaymentService payments;

    public CheckoutController(CheckoutService checkout, PaymentService payments) {
        this.checkout = checkout;
        this.payments = payments;
    }

    @Operation(summary = "Place an order from Your Stack or Buy now",
            description = "Send an Idempotency-Key header (8-40 chars) so retries never create a second order. "
                    + "ONLINE: stock is held for 15 minutes; open Razorpay Checkout with the response. "
                    + "COD: the order is confirmed immediately.")
    @PostMapping("/create-order")
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutResponse createOrder(@AuthenticationPrincipal AuthUser caller,
                                        @Valid @RequestBody CheckoutRequest req,
                                        @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return checkout.createOrder(caller, req, key);
    }

    @Operation(summary = "Confirm a payment with the signature from Razorpay Checkout")
    @PostMapping("/verify")
    public OrderQueryService.OrderDetail verify(@AuthenticationPrincipal AuthUser caller,
                                                @Valid @RequestBody VerifyRequest req) {
        return payments.verify(caller, req);
    }

    @Operation(summary = "Re-open payment for an unpaid order still inside its 15 minutes")
    @PostMapping("/{orderNumber}/retry")
    public CheckoutResponse retry(@AuthenticationPrincipal AuthUser caller, @PathVariable String orderNumber) {
        return checkout.retry(caller, orderNumber);
    }
}
