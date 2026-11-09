package com.bookmarked.cart;

import com.bookmarked.cart.PricingService.PricedLine;
import com.bookmarked.cart.PricingService.Summary;
import com.bookmarked.catalog.BookFormat;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PricingServiceTest {

    private final PricingService pricing = new PricingService(new BigDecimal("40"), new BigDecimal("499"));

    private static PricedLine line(BookFormat format, String mrp, String price, int qty) {
        return new PricedLine(format, new BigDecimal(mrp), new BigDecimal(price), qty);
    }

    @Test
    void free_delivery_at_or_above_the_threshold() {
        // The spec example: Clean Code ₹699 (MRP 899) + Atomic Habits ₹499 (MRP 699)
        Summary s = pricing.summarize(List.of(
                line(BookFormat.PAPERBACK, "899", "699", 1),
                line(BookFormat.PAPERBACK, "699", "499", 1)));
        assertThat(s.itemCount()).isEqualTo(2);
        assertThat(s.mrpTotal()).isEqualByComparingTo("1598");
        assertThat(s.discount()).isEqualByComparingTo("400");
        assertThat(s.subtotal()).isEqualByComparingTo("1198");
        assertThat(s.deliveryFee()).isEqualByComparingTo("0");
        assertThat(s.total()).isEqualByComparingTo("1198");
        assertThat(s.amountForFreeDelivery()).isEqualByComparingTo("0");

        Summary exactly = pricing.summarize(List.of(line(BookFormat.PAPERBACK, "499", "499", 1)));
        assertThat(exactly.deliveryFee()).isEqualByComparingTo("0");
    }

    @Test
    void delivery_fee_below_the_threshold() {
        Summary s = pricing.summarize(List.of(line(BookFormat.PAPERBACK, "350", "299", 1)));
        assertThat(s.deliveryFee()).isEqualByComparingTo("40");
        assertThat(s.total()).isEqualByComparingTo("339");
        assertThat(s.amountForFreeDelivery()).isEqualByComparingTo("200");
    }

    @Test
    void ebook_only_orders_never_pay_delivery() {
        Summary s = pricing.summarize(List.of(line(BookFormat.EBOOK, "299", "199", 1)));
        assertThat(s.deliveryFee()).isEqualByComparingTo("0");
        assertThat(s.total()).isEqualByComparingTo("199");
        assertThat(s.amountForFreeDelivery()).isEqualByComparingTo("0");
    }

    @Test
    void mixed_orders_use_the_whole_subtotal_and_quantities() {
        Summary s = pricing.summarize(List.of(
                line(BookFormat.PAPERBACK, "200", "150", 2),
                line(BookFormat.EBOOK, "150", "100", 1)));
        assertThat(s.itemCount()).isEqualTo(3);
        assertThat(s.subtotal()).isEqualByComparingTo("400");
        assertThat(s.deliveryFee()).isEqualByComparingTo("40");
        assertThat(s.total()).isEqualByComparingTo("440");
    }

    @Test
    void empty_stack_costs_nothing() {
        Summary s = pricing.summarize(List.of());
        assertThat(s.itemCount()).isZero();
        assertThat(s.total()).isEqualByComparingTo("0");
        assertThat(s.deliveryFee()).isEqualByComparingTo("0");
    }
}
