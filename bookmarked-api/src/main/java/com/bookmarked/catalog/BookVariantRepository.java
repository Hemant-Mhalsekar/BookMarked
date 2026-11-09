package com.bookmarked.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BookVariantRepository extends JpaRepository<BookVariant, Long> {

    boolean existsByIsbn(String isbn);

    boolean existsByIsbnAndIdNot(String isbn, Long id);
}
