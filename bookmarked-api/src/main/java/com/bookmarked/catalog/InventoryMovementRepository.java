package com.bookmarked.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {

    List<InventoryMovement> findByVariantIdOrderByCreatedAtDesc(Long variantId);
}
