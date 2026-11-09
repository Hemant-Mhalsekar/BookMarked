package com.bookmarked.order;

import com.bookmarked.catalog.BookFormat;
import com.bookmarked.catalog.BookVariant;
import com.bookmarked.order.OrderEnums.FulfillmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** One line of an order, with the title and prices frozen at checkout. */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private PurchaseOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id")
    private BookVariant variant;

    @Column(name = "title_snapshot", nullable = false, length = 255)
    private String titleSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BookFormat format;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal mrp;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "discount_share", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountShare = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_status", nullable = false, length = 20)
    private FulfillmentStatus fulfillmentStatus = FulfillmentStatus.PLACED;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "shipment_id")
    private Long shipmentId;

    protected OrderItem() {
    }

    public OrderItem(PurchaseOrder order, BookVariant variant, int quantity) {
        this.order = order;
        this.variant = variant;
        this.quantity = quantity;
        this.titleSnapshot = variant.getBook().getTitle();
        this.format = variant.getFormat();
        this.mrp = variant.getMrp();
        this.unitPrice = variant.getSellingPrice();
    }

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public Long getId() {
        return id;
    }

    public PurchaseOrder getOrder() {
        return order;
    }

    public BookVariant getVariant() {
        return variant;
    }

    public String getTitleSnapshot() {
        return titleSnapshot;
    }

    public BookFormat getFormat() {
        return format;
    }

    public BigDecimal getMrp() {
        return mrp;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getDiscountShare() {
        return discountShare;
    }

    public FulfillmentStatus getFulfillmentStatus() {
        return fulfillmentStatus;
    }

    public void setFulfillmentStatus(FulfillmentStatus fulfillmentStatus) {
        this.fulfillmentStatus = fulfillmentStatus;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public Long getShipmentId() {
        return shipmentId;
    }

    public void setShipmentId(Long shipmentId) {
        this.shipmentId = shipmentId;
    }

    /** What the customer actually paid for this line (price after any coupon share). */
    public java.math.BigDecimal paidAmount() {
        return lineTotal().subtract(discountShare == null ? java.math.BigDecimal.ZERO : discountShare);
    }

    public boolean isCancellable() {
        return format.isPhysical() && (fulfillmentStatus == OrderEnums.FulfillmentStatus.PLACED
                || fulfillmentStatus == OrderEnums.FulfillmentStatus.PROCESSING);
    }
}
