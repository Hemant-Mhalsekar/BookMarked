package com.bookmarked.catalog;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuthorRepository extends JpaRepository<Author, Long> {

    Optional<Author> findFirstByNameIgnoreCase(String name);

    List<Author> findByNameContainingIgnoreCaseOrderByName(String name, Pageable pageable);
}
