package com.bookmarked.catalog;

import com.bookmarked.config.MySqlFunctionContributor;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Dynamic catalog filters built with the Criteria API.
 * <p>
 * Every to-many condition (authors, categories, variants) is an EXISTS subquery
 * rather than a join, so a book never appears twice and no DISTINCT is needed
 * (DISTINCT would clash with ORDER BY on the full-text score in MySQL).
 */
public final class BookSpecifications {

    private BookSpecifications() {
    }

    /**
     * Customer-facing search: only active books that have at least one active variant.
     *
     * @param categoryIds the requested categories plus their subcategories; null when not filtering by category
     */
    public static Specification<Book> publicSearch(BookSearchCriteria c, Collection<Long> categoryIds) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.isTrue(root.get("active")));
            where.add(cb.isNotNull(root.get("fromPrice")));   // has at least one active variant

            Expression<Double> score = null;
            if (c.q() != null) {
                SearchParts parts = matchQuery(root, query, cb, c.q());
                where.add(parts.predicate());
                score = parts.score();
            }

            if (categoryIds != null) {
                where.add(categoryIds.isEmpty() ? cb.disjunction() : inCategories(root, query, cb, categoryIds));
            }
            if (c.author() != null) {
                where.add(authorLike(root, query, cb, c.author()));
            }
            if (c.minRating() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("avgRating"), c.minRating()));
            }
            if (c.featuredOnly()) {
                where.add(cb.isTrue(root.get("featured")));
            }
            if (c.format() != null || c.minPrice() != null || c.maxPrice() != null || c.inStock()) {
                where.add(hasMatchingVariant(root, query, cb, c.format(), c.minPrice(), c.maxPrice(), c.inStock()));
            }

            boolean isCountQuery = Long.class.equals(query.getResultType()) || long.class.equals(query.getResultType());
            if (!isCountQuery && c.sort() == BookSearchCriteria.SortOption.RELEVANCE && c.q() != null) {
                List<Order> order = new ArrayList<>();
                if (score != null) {
                    order.add(cb.desc(score));
                }
                order.add(cb.desc(root.get("ratingCount")));
                order.add(cb.desc(root.get("id")));
                query.orderBy(order);
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /**
     * Staff search: all books (optionally only active / inactive), matching title,
     * author or ISBN. No full-text so that inactive books and odd queries still match.
     */
    public static Specification<Book> adminSearch(String q, Boolean active) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (active != null) {
                where.add(cb.equal(root.get("active"), active));
            }
            if (q != null && !q.isBlank()) {
                String like = CatalogText.likeContains(q.trim());
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("title")), like, CatalogText.LIKE_ESCAPE));
                any.add(authorLike(root, query, cb, q.trim()));
                CatalogText.normalizeIsbn(q).ifPresent(isbn -> any.add(hasIsbn(root, query, cb, isbn)));
                where.add(cb.or(any.toArray(Predicate[]::new)));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    // ------------------------------------------------------------------

    private record SearchParts(Predicate predicate, Expression<Double> score) {
    }

    /** Title contains, author contains, exact ISBN, or full-text match on title + description. */
    private static SearchParts matchQuery(Root<Book> root, CriteriaQuery<?> query, CriteriaBuilder cb, String q) {
        String like = CatalogText.likeContains(q);
        List<Predicate> any = new ArrayList<>();
        any.add(cb.like(cb.lower(root.get("title")), like, CatalogText.LIKE_ESCAPE));
        any.add(authorLike(root, query, cb, q));
        CatalogText.normalizeIsbn(q).ifPresent(isbn -> any.add(hasIsbn(root, query, cb, isbn)));

        Expression<Double> score = null;
        Optional<String> terms = CatalogText.booleanFullTextQuery(q);
        if (terms.isPresent()) {
            score = cb.function(MySqlFunctionContributor.MATCH_AGAINST, Double.class,
                    root.get("title"), root.get("description"), cb.literal(terms.get()));
            any.add(cb.greaterThan(score, 0.0));
        }
        return new SearchParts(cb.or(any.toArray(Predicate[]::new)), score);
    }

    private static Predicate authorLike(Root<Book> root, CriteriaQuery<?> query, CriteriaBuilder cb, String text) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<Book> book = sub.correlate(root);
        Join<Book, Author> author = book.join("authors");
        sub.select(author.<Long>get("id"))
                .where(cb.like(cb.lower(author.get("name")), CatalogText.likeContains(text), CatalogText.LIKE_ESCAPE));
        return cb.exists(sub);
    }

    private static Predicate inCategories(Root<Book> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                          Collection<Long> categoryIds) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<Book> book = sub.correlate(root);
        Join<Book, Category> category = book.join("categories");
        sub.select(category.<Long>get("id")).where(category.get("id").in(categoryIds));
        return cb.exists(sub);
    }

    private static Predicate hasIsbn(Root<Book> root, CriteriaQuery<?> query, CriteriaBuilder cb, String isbn) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<BookVariant> v = sub.from(BookVariant.class);
        sub.select(v.<Long>get("id")).where(cb.equal(v.get("book"), root), cb.equal(v.get("isbn"), isbn));
        return cb.exists(sub);
    }

    /** One active variant must satisfy format, price range and stock together. */
    private static Predicate hasMatchingVariant(Root<Book> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                                BookFormat format, BigDecimal minPrice, BigDecimal maxPrice,
                                                boolean inStock) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<BookVariant> v = sub.from(BookVariant.class);
        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(v.get("book"), root));
        where.add(cb.isTrue(v.get("active")));
        if (format != null) {
            where.add(cb.equal(v.get("format"), format));
        }
        if (minPrice != null) {
            where.add(cb.greaterThanOrEqualTo(v.get("sellingPrice"), minPrice));
        }
        if (maxPrice != null) {
            where.add(cb.lessThanOrEqualTo(v.get("sellingPrice"), maxPrice));
        }
        if (inStock) {
            where.add(cb.or(cb.equal(v.get("format"), BookFormat.EBOOK), cb.greaterThan(v.<Integer>get("stock"), 0)));
        }
        sub.select(v.<Long>get("id")).where(where.toArray(Predicate[]::new));
        return cb.exists(sub);
    }
}
