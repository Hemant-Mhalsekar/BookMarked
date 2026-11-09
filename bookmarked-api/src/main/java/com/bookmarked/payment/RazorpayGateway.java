package com.bookmarked.payment;

/**
 * The calls BookMarked makes to Razorpay. Tests replace this with a mock, so
 * they never need real keys or network access.
 */
public interface RazorpayGateway {

    /**
     * Creates a Razorpay order for the amount the customer will pay.
     *
     * @param amountPaise total in paise (₹699.00 = 69900)
     * @param receipt     our order number, shown in the Razorpay dashboard
     * @return the Razorpay order id (order_...)
     */
    String createOrder(long amountPaise, String receipt);

    /**
     * Refunds a captured payment (fully or partly).
     *
     * @return the Razorpay refund id (rfnd_...)
     */
    String refund(String paymentId, long amountPaise);

    /** Thrown when Razorpay can't be reached or rejects a call. */
    class GatewayException extends RuntimeException {
        public GatewayException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
