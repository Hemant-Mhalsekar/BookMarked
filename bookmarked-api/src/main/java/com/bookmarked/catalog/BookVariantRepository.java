package com.bookmarked.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookVariantRepository extends JpaRepository<BookVariant, Long> {

    boolean existsByIsbn(String isbn);

    boolean existsByIsbnAndIdNot(String isbn, Long id);

    /**
     * Takes {@code qty} copies out of stock in one atomic statement. The WHERE clause
     * makes overselling impossible even when many buyers race for the last copy:
     * MySQL row-locks the variant, so exactly one of them sees enough stock.
     *
     * @return 1 if reserved, 0 if there wasn't enough stock
     */
    @Modifying(flushAutomatically = true)
    @Query("update BookVariant v set v.stock = v.stock - :qty where v.id = :id and v.stock >= :qty")
    int reserveStock(@Param("id") Long id, @Param("qty") int qty);

    /** Puts copies back (reservation expired, order cancelled, return restocked). */
    @Modifying(flushAutomatically = true)
    @Query("update BookVariant v set v.stock = v.stock + :qty where v.id = :id and v.stock is not null")
    int releaseStock(@Param("id") Long id, @Param("qty") int qty);
}
