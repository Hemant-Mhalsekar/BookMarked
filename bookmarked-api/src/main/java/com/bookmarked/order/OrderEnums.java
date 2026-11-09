package com.bookmarked.order;

/** Order, payment and item states (spec section 8). */
public final class OrderEnums {

    private OrderEnums() {
    }

    public enum OrderStatus {
        PENDING_PAYMENT, CONFIRMED, COMPLETED, CANCELLED, EXPIRED
    }

    public enum PaymentMethod {
        ONLINE, COD
    }

    public enum PaymentStatus {
        PENDING, PAID, FAILED, COD_PENDING, COLLECTED, PARTIALLY_REFUNDED, REFUNDED
    }

    public enum FulfillmentStatus {
        PLACED, PROCESSING, SHIPPED, DELIVERED, CANCELLED, RETURN_REQUESTED, RETURNED
    }
}
