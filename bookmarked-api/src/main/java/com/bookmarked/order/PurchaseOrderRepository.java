package com.bookmarked.order;

import com.bookmarked.order.OrderEnums.OrderStatus;
import com.bookmarked.order.OrderEnums.PaymentMethod;
import com.bookmarked.order.OrderEnums.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    Optional<PurchaseOrder> findByOrderNumberAndUserId(String orderNumber, Long userId);

    Optional<PurchaseOrder> findByRazorpayOrderId(String razorpayOrderId);

    Optional<PurchaseOrder> findByIdempotencyKey(String idempotencyKey);

    Page<PurchaseOrder> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    List<PurchaseOrder> findTop100ByStatusAndReservedUntilBefore(OrderStatus status, Instant cutoff);

    long countByUserIdAndPaymentMethodAndPaymentStatusAndStatusNot(Long userId, PaymentMethod method,
                                                                  PaymentStatus paymentStatus, OrderStatus status);

    /**
     * The idempotency point for payments: only the first caller (checkout verify or
     * webhook) moves the order out of PENDING_PAYMENT. Returns 1 for that caller, 0 for others.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PurchaseOrder o
            set o.status = :confirmed, o.paymentStatus = :paid, o.razorpayPaymentId = :paymentId,
                o.paidAt = :now, o.updatedAt = :now
            where o.id = :id and o.status = :pending
            """)
    int markPaid(@Param("id") Long id, @Param("paymentId") String paymentId, @Param("now") Instant now,
                 @Param("pending") OrderStatus pending, @Param("confirmed") OrderStatus confirmed,
                 @Param("paid") PaymentStatus paid);

    /** Expires an unpaid order exactly once (the expiry job and a late payment may race). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PurchaseOrder o set o.status = :expired, o.updatedAt = :now
            where o.id = :id and o.status = :pending
            """)
    int expire(@Param("id") Long id, @Param("now") Instant now,
               @Param("pending") OrderStatus pending, @Param("expired") OrderStatus expired);
}
