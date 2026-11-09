package com.bookmarked.cart;

import com.bookmarked.catalog.BookFormat;
import com.bookmarked.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * The single place where order money is worked out. Your Stack uses it now and
 * checkout will reuse it, so the total a customer sees is the total they pay.
 * <p>
 * Delivery: a flat fee for orders with physical books below the free-delivery
 * threshold; e-book-only orders never pay delivery.
 */
@Service
public class PricingService {

    private final BigDecimal deliveryFee;
    private final BigDecimal freeDeliveryThreshold;

    @Autowired
    public PricingService(AppProperties props) {
        this(props.business().deliveryFee(), props.business().freeDeliveryThreshold());
    }

    public PricingService(BigDecimal deliveryFee, BigDecimal freeDeliveryThreshold) {
        this.deliveryFee = deliveryFee;
        this.freeDeliveryThreshold = freeDeliveryThreshold;
    }

    /** A line to be charged. Lines with problems (out of stock, unavailable) are not passed in. */
    public record PricedLine(BookFormat format, BigDecimal mrp, BigDecimal unitPrice, int quantity) {
    }

    public record Summary(
            int itemCount,
            BigDecimal mrpTotal,
            BigDecimal discount,
            BigDecimal subtotal,
            BigDecimal deliveryFee,
            BigDecimal total,
            BigDecimal freeDeliveryThreshold,
            BigDecimal amountForFreeDelivery) {
    }

    public Summary summarize(List<PricedLine> lines) {
        int count = 0;
        BigDecimal mrpTotal = BigDecimal.ZERO;
        BigDecimal subtotal = BigDecimal.ZERO;
        boolean hasPhysical = false;
        for (PricedLine line : lines) {
            BigDecimal qty = BigDecimal.valueOf(line.quantity());
            count += line.quantity();
            mrpTotal = mrpTotal.add(line.mrp().multiply(qty));
            subtotal = subtotal.add(line.unitPrice().multiply(qty));
            hasPhysical |= line.format().isPhysical();
        }
        boolean free = !hasPhysical || subtotal.compareTo(freeDeliveryThreshold) >= 0;
        BigDecimal delivery = free ? BigDecimal.ZERO : deliveryFee;
        BigDecimal toFree = hasPhysical && !free ? freeDeliveryThreshold.subtract(subtotal) : BigDecimal.ZERO;
        return new Summary(count, money(mrpTotal), money(mrpTotal.subtract(subtotal)), money(subtotal),
                money(delivery), money(subtotal.add(delivery)), money(freeDeliveryThreshold), money(toFree));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
