package com.bookmarked.order;

import com.bookmarked.order.OrderEnums.OrderStatus;
import com.bookmarked.order.OrderEnums.PaymentMethod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class CheckoutDtos {

    private CheckoutDtos() {
    }

    /** CART buys everything in Your Stack; BUY_NOW buys one format directly. */
    public enum Source {
        CART, BUY_NOW
    }

    public record CheckoutRequest(
            Long addressId,
            @NotNull(message = "Choose a payment method") PaymentMethod paymentMethod,
            @NotNull Source source,
            Long variantId,
            @Min(1) @Max(10) Integer quantity) {
    }

    public record Prefill(String name, String email, String contact) {
    }

    /**
     * What the browser needs next. For ONLINE orders: open Razorpay Checkout with
     * keyId + razorpayOrderId + amountPaise. For COD the order is already confirmed.
     */
    public record CheckoutResponse(
            String orderNumber,
            OrderStatus status,
            PaymentMethod paymentMethod,
            BigDecimal total,
            Long amountPaise,
            String currency,
            String razorpayOrderId,
            String keyId,
            Instant reservedUntil,
            Prefill prefill) {
    }

    /** Exactly what Razorpay Checkout hands to its success handler. */
    public record VerifyRequest(
            @NotBlank @Size(max = 40) String razorpayOrderId,
            @NotBlank @Size(max = 40) String razorpayPaymentId,
            @NotBlank @Size(max = 128) String razorpaySignature) {
    }
}
