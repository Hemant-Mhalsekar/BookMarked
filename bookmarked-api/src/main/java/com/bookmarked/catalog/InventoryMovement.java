package com.bookmarked.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Append-only stock ledger: every change to a variant's stock is one row,
 * so "why is the stock 7?" always has an answer.
 */
@Entity
@Table(name = "inventory_movements")
public class InventoryMovement {

    public enum Reason {
        RESTOCK, SALE, RESERVATION, RELEASE, CANCEL, RETURN, ADJUSTMENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "variant_id", nullable = false)
    private Long variantId;

    @Column(name = "change_qty", nullable = false)
    private int changeQty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Reason reason;

    @Column(name = "reference_id", length = 40)
    private String referenceId;

    @Column(length = 255)
    private String note;

    /** Null when the system made the change (e.g. reservation expiry). */
    @Column(name = "performed_by")
    private Long performedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected InventoryMovement() {
    }

    public InventoryMovement(Long variantId, int changeQty, Reason reason, String referenceId, String note,
                             Long performedBy) {
        this.variantId = variantId;
        this.changeQty = changeQty;
        this.reason = reason;
        this.referenceId = referenceId;
        this.note = note;
        this.performedBy = performedBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getVariantId() {
        return variantId;
    }

    public int getChangeQty() {
        return changeQty;
    }

    public Reason getReason() {
        return reason;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public String getNote() {
        return note;
    }

    public Long getPerformedBy() {
        return performedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
