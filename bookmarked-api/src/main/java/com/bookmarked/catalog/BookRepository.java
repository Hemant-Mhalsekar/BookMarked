package com.bookmarked.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookRepository extends JpaRepository<Book, Long>, JpaSpecificationExecutor<Book> {

    Optional<Book> findBySlug(String slug);

    Optional<Book> findBySlugAndActiveTrue(String slug);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    /** Other books by any of these authors, most-reviewed first. */
    @Query("""
            select distinct b from Book b join b.authors a
            where a.id in :authorIds and b.id <> :bookId and b.active = true and b.fromPrice is not null
            order by b.ratingCount desc, b.id desc
            """)
    List<Book> findByAuthorsExcluding(@Param("authorIds") java.util.Collection<Long> authorIds,
                                      @Param("bookId") Long bookId,
                                      org.springframework.data.domain.Pageable pageable);

    /** Books sharing a category, excluding the given ids, most-reviewed first. */
    @Query("""
            select distinct b from Book b join b.categories c
            where c.id in :categoryIds and b.id not in :excludeIds and b.active = true and b.fromPrice is not null
            order by b.ratingCount desc, b.id desc
            """)
    List<Book> findInCategoriesExcluding(@Param("categoryIds") java.util.Collection<Long> categoryIds,
                                         @Param("excludeIds") java.util.Collection<Long> excludeIds,
                                         org.springframework.data.domain.Pageable pageable);

    /** Title-prefix and title-contains matches for the search box dropdown. */
    @Query("""
            select b.title from Book b
            where b.active = true and lower(b.title) like lower(concat('%', :q, '%'))
            order by case when lower(b.title) like lower(concat(:q, '%')) then 0 else 1 end, b.ratingCount desc, b.title
            """)
    List<String> suggestTitles(@Param("q") String q, org.springframework.data.domain.Pageable pageable);
}
