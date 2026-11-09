package com.bookmarked.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Razorpay signatures are hex HMAC-SHA256:
 * <ul>
 *   <li>Checkout: HMAC(order_id + "|" + payment_id, key_secret)</li>
 *   <li>Webhook: HMAC(raw request body, webhook_secret)</li>
 * </ul>
 * Comparison is constant-time so timing doesn't leak how much of a guess was right.
 */
public final class Signatures {

    private Signatures() {
    }

    public static String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    public static boolean matches(String payload, String secret, String signature) {
        if (payload == null || secret == null || secret.isEmpty() || signature == null) {
            return false;
        }
        byte[] expected = hmacSha256Hex(payload, secret).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    public static boolean checkoutSignatureValid(String orderId, String paymentId, String signature, String keySecret) {
        return matches(orderId + "|" + paymentId, keySecret, signature);
    }
}
