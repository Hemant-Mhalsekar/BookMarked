package com.bookmarked.catalog.dto;

import com.bookmarked.catalog.Book;
import com.bookmarked.catalog.BookFormat;
import com.bookmarked.catalog.BookVariant;
import com.bookmarked.catalog.Category;
import com.bookmarked.catalog.StockStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Public (customer-facing) catalog responses. Exact stock numbers are never
 * exposed; customers see a {@link StockStatus} and, when low, how many are left.
 */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record AuthorRef(Long id, String name) {
    }

    public record CategoryRef(Long id, String name, String slug) {
        public static CategoryRef from(Category c) {
            return new CategoryRef(c.getId(), c.getName(), c.getSlug());
        }
    }

    public record CategoryNode(Long id, String name, String slug, List<CategoryNode> children) {
    }

    /** Listing card: enough to render a book tile. */
    public record BookCard(
            Long id,
            String slug,
            String title,
            List<String> authors,
            String coverImageUrl,
            BigDecimal fromPrice,
            BigDecimal mrp,
            int discountPercent,
            BigDecimal avgRating,
            int ratingCount,
            List<BookFormat> formats,
            List<String> badges,
            Integer stockLeft) {

        public static final String BADGE_EBOOK = "EBOOK_AVAILABLE";
        public static final String BADGE_LOW_STOCK = "LOW_STOCK";
        public static final String BADGE_OUT_OF_STOCK = "OUT_OF_STOCK";

        public static BookCard from(Book book) {
            List<BookVariant> active = book.activeVariants();
            BookVariant cheapest = book.cheapestVariant().orElse(null);

            List<String> badges = new ArrayList<>();
            boolean hasEbook = active.stream().anyMatch(v -> v.getFormat() == BookFormat.EBOOK);
            if (hasEbook) {
                badges.add(BADGE_EBOOK);
            }
            List<BookVariant> physical = active.stream().filter(v -> v.getFormat().isPhysical()).toList();
            boolean anyInStock = physical.stream().anyMatch(v -> v.stockStatus() == StockStatus.IN_STOCK);
            Integer stockLeft = null;
            if (!anyInStock) {
                stockLeft = physical.stream()
                        .filter(v -> v.stockStatus() == StockStatus.LOW_STOCK)
                        .map(BookVariant::getStock)
                        .max(Comparator.naturalOrder())
                        .orElse(null);
                if (stockLeft != null) {
                    badges.add(BADGE_LOW_STOCK);
                }
            }
            if (active.stream().noneMatch(BookVariant::isPurchasable)) {
                badges.add(BADGE_OUT_OF_STOCK);
            }

            return new BookCard(
                    book.getId(),
                    book.getSlug(),
                    book.getTitle(),
                    book.authorNames(),
                    book.getCoverImageUrl(),
                    cheapest == null ? null : cheapest.getSellingPrice(),
                    cheapest == null ? null : cheapest.getMrp(),
                    cheapest == null ? 0 : cheapest.discountPercent(),
                    book.getAvgRating(),
                    book.getRatingCount(),
                    active.stream().map(BookVariant::getFormat).toList(),
                    badges,
                    stockLeft);
        }
    }

    public record VariantView(
            Long id,
            BookFormat format,
            String isbn,
            Integer pages,
            BigDecimal mrp,
            BigDecimal sellingPrice,
            int discountPercent,
            StockStatus stockStatus,
            Integer stockLeft) {

        public static VariantView from(BookVariant v) {
            StockStatus status = v.stockStatus();
            return new VariantView(v.getId(), v.getFormat(), v.getIsbn(), v.getPages(), v.getMrp(),
                    v.getSellingPrice(), v.discountPercent(), status,
                    status == StockStatus.LOW_STOCK ? v.getStock() : null);
        }
    }

    public record BookDetail(
            Long id,
            String slug,
            String title,
            String description,
            String language,
            String publisher,
            Integer publishedYear,
            String coverImageUrl,
            BigDecimal avgRating,
            int ratingCount,
            List<AuthorRef> authors,
            List<CategoryRef> categories,
            List<VariantView> variants) {

        public static BookDetail from(Book b) {
            return new BookDetail(
                    b.getId(), b.getSlug(), b.getTitle(), b.getDescription(), b.getLanguage(), b.getPublisher(),
                    b.getPublishedYear(), b.getCoverImageUrl(), b.getAvgRating(), b.getRatingCount(),
                    b.getAuthors().stream().map(a -> new AuthorRef(a.getId(), a.getName())).toList(),
                    b.getCategories().stream().filter(Category::isActive).map(CategoryRef::from).toList(),
                    b.activeVariants().stream().map(VariantView::from).toList());
        }
    }
}
