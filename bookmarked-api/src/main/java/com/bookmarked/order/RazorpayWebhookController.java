package com.bookmarked.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Razorpay → BookMarked. Public (Razorpay has no login), so every request must
 * carry a valid X-Razorpay-Signature over the exact raw body; the body is taken
 * as a String so nothing re-serialises it before the check.
 */
@RestController
@Tag(name = "Webhooks")
public class RazorpayWebhookController {

    private final PaymentService payments;

    public RazorpayWebhookController(PaymentService payments) {
        this.payments = payments;
    }

    @Operation(summary = "Razorpay webhook (payment.captured, order.paid, payment.failed, refund.processed)")
    @PostMapping(path = "/api/webhooks/razorpay")
    public ResponseEntity<Map<String, String>> handle(
            @RequestBody String body,
            @RequestHeader(name = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(name = "X-Razorpay-Event-Id", required = false) String eventId) {
        PaymentService.Outcome outcome = payments.handleWebhook(body, signature, eventId);
        return ResponseEntity.ok(Map.of("status", outcome.name()));
    }
}
