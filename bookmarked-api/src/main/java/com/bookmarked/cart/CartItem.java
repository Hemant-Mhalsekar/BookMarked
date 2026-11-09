package com.bookmarked.cart;

import com.bookmarked.catalog.BookVariant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One line in the Stack. Holds no price for charging: prices are always read live
 * from the variant. {@code priceAtAdd} only powers the "price changed" notice.
 */
@Entity
@Table(name = "cart_items")
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id")
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id")
    private BookVariant variant;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "price_at_add", precision = 10, scale = 2)
    private BigDecimal priceAtAdd;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    protected CartItem() {
    }

    public CartItem(Cart cart, BookVariant variant, int quantity) {
        this.cart = cart;
        this.variant = variant;
        this.quantity = quantity;
        this.priceAtAdd = variant.getSellingPrice();
        this.addedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Cart getCart() {
        return cart;
    }

    public BookVariant getVariant() {
        return variant;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getPriceAtAdd() {
        return priceAtAdd;
    }

    public void setPriceAtAdd(BigDecimal priceAtAdd) {
        this.priceAtAdd = priceAtAdd;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
