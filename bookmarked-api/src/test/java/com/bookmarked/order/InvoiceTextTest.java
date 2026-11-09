package com.bookmarked.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** The PDF's built-in fonts can't draw every character, so invoice text is cleaned first. */
class InvoiceTextTest {

    @Test
    void replaces_characters_the_built_in_fonts_cannot_draw() {
        assertThat(InvoiceService.clean("“Clean” Code – Martin’s guide…")).isEqualTo("\"Clean\" Code - Martin's guide...");
        assertThat(InvoiceService.clean("₹699 × 2")).isEqualTo("Rs.699 x 2");
        assertThat(InvoiceService.clean("Café Mädchen")).isEqualTo("Café Mädchen");
        assertThat(InvoiceService.clean("गोदान Godaan")).isEqualTo("????? Godaan");
        assertThat(InvoiceService.clean("tab\there")).isEqualTo("tab here");
        assertThat(InvoiceService.clean(null)).isEmpty();
    }

    @Test
    void formats_rupees_with_two_decimals() {
        assertThat(InvoiceService.money(new BigDecimal("1198"))).isEqualTo("Rs. 1,198.00");
        assertThat(InvoiceService.money(new BigDecimal("40.5"))).isEqualTo("Rs. 40.50");
        assertThat(InvoiceService.money(new BigDecimal("0"))).isEqualTo("Rs. 0.00");
    }
}
