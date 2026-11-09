package com.bookmarked.order;

import com.bookmarked.order.OrderEnums.OrderStatus;
import com.bookmarked.order.OrderEnums.PaymentMethod;
import com.bookmarked.order.OrderEnums.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    Optional<PurchaseOrder> findByOrderNumberAndUserId(String orderNumber, Long userId);

    Optional<PurchaseOrder> findByRazorpayOrderId(String razorpayOrderId);

    Optional<PurchaseOrder> findByIdempotencyKey(String idempotencyKey);

    Page<PurchaseOrder> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** Customer "My orders" tabs. */
    Page<PurchaseOrder> findByUserIdAndStatusInOrderByCreatedAtDesc(Long userId, Collection<OrderStatus> statuses,
                                                                   Pageable pageable);

    @Query("select o.id from PurchaseOrder o where o.orderNumber = :orderNumber")
    Optional<Long> findIdByOrderNumber(@Param("orderNumber") String orderNumber);

    @Query("select o.id from PurchaseOrder o where o.orderNumber = :orderNumber and o.userId = :userId")
    Optional<Long> findIdByOrderNumberAndUserId(@Param("orderNumber") String orderNumber, @Param("userId") Long userId);

    /**
     * SELECT … FOR UPDATE on the order row. Every change after checkout (staff status
     * updates, cancellations, refunds) takes this lock first, so two of them on the
     * same order run one after the other and can't refund the same item twice.
     * Call it before anything else in the transaction loads the order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrder o where o.id = :id")
    Optional<PurchaseOrder> lockById(@Param("id") Long id);

    /** Staff order search. {@code q} is a LIKE pattern (already escaped with '!') or null. */
    @Query(value = """
            select o from PurchaseOrder o
            where (:status is null or o.status = :status)
              and (:itemStatus is null or exists (select 1 from OrderItem i where i.order = o and i.fulfillmentStatus = :itemStatus))
              and (:q is null or o.orderNumber like :q escape '!'
                   or o.userId in (select u.id from User u where lower(u.email) like :q escape '!' or u.phone like :q escape '!'))
            order by o.createdAt desc
            """,
            countQuery = """
            select count(o) from PurchaseOrder o
            where (:status is null or o.status = :status)
              and (:itemStatus is null or exists (select 1 from OrderItem i where i.order = o and i.fulfillmentStatus = :itemStatus))
              and (:q is null or o.orderNumber like :q escape '!'
                   or o.userId in (select u.id from User u where lower(u.email) like :q escape '!' or u.phone like :q escape '!'))
            """)
    Page<PurchaseOrder> adminSearch(@Param("status") OrderStatus status,
                                    @Param("itemStatus") OrderEnums.FulfillmentStatus itemStatus,
                                    @Param("q") String q, Pageable pageable);

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
