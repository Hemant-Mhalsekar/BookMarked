package com.bookmarked.catalog;

import com.bookmarked.common.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Validated query parameters for GET /api/books.
 */
public record BookSearchCriteria(
        String q,
        List<String> categorySlugs,
        String author,
        BookFormat format,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        BigDecimal minRating,
        boolean inStock,
        boolean featuredOnly,
        SortOption sort,
        int page,
        int size) {

    public static final int MAX_PAGE_SIZE = 48;

    public enum SortOption {
        RELEVANCE, PRICE_ASC, PRICE_DESC, NEWEST, RATING;

        static SortOption parse(String raw) {
            if (!StringUtils.hasText(raw)) {
                return RELEVANCE;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("INVALID_SORT",
                        "sort must be one of relevance, price_asc, price_desc, newest, rating");
            }
        }
    }

    /** Builds criteria from raw request parameters, rejecting impossible values with 400. */
    public static BookSearchCriteria of(String q, String category, String author, String format,
                                        BigDecimal minPrice, BigDecimal maxPrice, BigDecimal minRating,
                                        Boolean inStock, Boolean featured, String sort, Integer page, Integer size) {
        BookFormat bookFormat = null;
        if (StringUtils.hasText(format)) {
            try {
                bookFormat = BookFormat.valueOf(format.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("INVALID_FORMAT", "format must be PAPERBACK, HARDCOVER or EBOOK");
            }
        }
        if (minPrice != null && minPrice.signum() < 0 || maxPrice != null && maxPrice.signum() < 0) {
            throw ApiException.badRequest("INVALID_PRICE", "Prices cannot be negative");
        }
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw ApiException.badRequest("INVALID_PRICE", "minPrice cannot be greater than maxPrice");
        }
        if (minRating != null && (minRating.signum() < 0 || minRating.compareTo(BigDecimal.valueOf(5)) > 0)) {
            throw ApiException.badRequest("INVALID_RATING", "minRating must be between 0 and 5");
        }
        int p = page == null ? 0 : page;
        int s = size == null ? 20 : size;
        if (p < 0 || p > 1000) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be between 0 and 1000");
        }
        if (s < 1 || s > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("INVALID_PAGE_SIZE", "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        List<String> slugs = !StringUtils.hasText(category) ? List.of()
                : Arrays.stream(category.split(","))
                .map(String::trim).filter(StringUtils::hasText)
                .map(sl -> sl.toLowerCase(Locale.ROOT)).distinct().limit(20).toList();
        String query = StringUtils.hasText(q) ? q.trim() : null;
        if (query != null && query.length() > 100) {
            query = query.substring(0, 100);
        }
        return new BookSearchCriteria(query, slugs, StringUtils.hasText(author) ? author.trim() : null, bookFormat,
                minPrice, maxPrice, minRating, Boolean.TRUE.equals(inStock), Boolean.TRUE.equals(featured),
                SortOption.parse(sort), p, s);
    }

    /**
     * Relevance ordering is applied inside the Specification (it needs the full-text
     * score), so it gets an unsorted page request. Other orders use plain columns.
     */
    public Pageable pageable() {
        Sort order = switch (sort) {
            case PRICE_ASC -> Sort.by(Sort.Order.asc("fromPrice"), Sort.Order.desc("id"));
            case PRICE_DESC -> Sort.by(Sort.Order.desc("fromPrice"), Sort.Order.desc("id"));
            case NEWEST -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
            case RATING -> Sort.by(Sort.Order.desc("avgRating"), Sort.Order.desc("ratingCount"), Sort.Order.desc("id"));
            case RELEVANCE -> q == null
                    ? Sort.by(Sort.Order.desc("featured"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
                    : Sort.unsorted();
        };
        return PageRequest.of(page, size, order);
    }
}
