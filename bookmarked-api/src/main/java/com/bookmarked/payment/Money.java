package com.bookmarked.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rupees in the database, paise at the Razorpay boundary. */
public final class Money {

    private Money() {
    }

    /** ₹1,198.00 → 119800. */
    public static long toPaise(BigDecimal rupees) {
        return rupees.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    public static BigDecimal fromPaise(long paise) {
        return BigDecimal.valueOf(paise).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
    }
}
