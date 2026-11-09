package com.bookmarked.cart;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A customer's Stack (cart). One per user, created on first use. */
@Entity
@Table(name = "carts")
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("addedAt ASC")
    private List<CartItem> items = new ArrayList<>();

    protected Cart() {
    }

    public Cart(Long userId) {
        this.userId = userId;
        this.updatedAt = Instant.now();
    }

    public void touch() {
        updatedAt = Instant.now();
    }

    public Optional<CartItem> itemForVariant(Long variantId) {
        return items.stream().filter(i -> i.getVariant().getId().equals(variantId)).findFirst();
    }

    public Optional<CartItem> item(Long itemId) {
        return items.stream().filter(i -> i.getId() != null && i.getId().equals(itemId)).findFirst();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<CartItem> getItems() {
        return items;
    }
}
