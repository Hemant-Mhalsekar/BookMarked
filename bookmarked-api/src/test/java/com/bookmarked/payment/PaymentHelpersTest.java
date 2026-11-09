package com.bookmarked.payment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentHelpersTest {

    @Test
    void hmac_matches_a_known_vector() {
        // RFC 4231-style check: HMAC-SHA256("The quick brown fox jumps over the lazy dog", "key")
        assertThat(Signatures.hmacSha256Hex("The quick brown fox jumps over the lazy dog", "key"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void checkout_signature_is_order_id_pipe_payment_id() {
        String secret = "test_secret";
        String sig = Signatures.hmacSha256Hex("order_ABC|pay_XYZ", secret);
        assertThat(Signatures.checkoutSignatureValid("order_ABC", "pay_XYZ", sig, secret)).isTrue();
        assertThat(Signatures.checkoutSignatureValid("order_ABC", "pay_XYZ", sig.toUpperCase(), secret)).isTrue();
        assertThat(Signatures.checkoutSignatureValid("order_ABC", "pay_OTHER", sig, secret)).isFalse();
        assertThat(Signatures.checkoutSignatureValid("order_ABC", "pay_XYZ", sig, "wrong")).isFalse();
        assertThat(Signatures.checkoutSignatureValid("order_ABC", "pay_XYZ", null, secret)).isFalse();
        assertThat(Signatures.matches("body", "", "anything")).isFalse();
    }

    @Test
    void rupees_to_paise_and_back() {
        assertThat(Money.toPaise(new BigDecimal("1198"))).isEqualTo(119800L);
        assertThat(Money.toPaise(new BigDecimal("699.50"))).isEqualTo(69950L);
        assertThat(Money.toPaise(new BigDecimal("0.01"))).isEqualTo(1L);
        assertThat(Money.fromPaise(119800L)).isEqualByComparingTo("1198.00");
        assertThatThrownBy(() -> Money.toPaise(new BigDecimal("1e20"))).isInstanceOf(ArithmeticException.class);
    }
}
