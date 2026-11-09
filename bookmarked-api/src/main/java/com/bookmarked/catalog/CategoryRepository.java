package com.bookmarked.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    List<Category> findAllByOrderByNameAsc();

    List<Category> findBySlugIn(Collection<String> slugs);

    List<Category> findByParentIdIn(Collection<Long> parentIds);
}
