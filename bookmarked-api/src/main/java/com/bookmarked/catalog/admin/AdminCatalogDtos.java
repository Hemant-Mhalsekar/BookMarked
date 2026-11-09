package com.bookmarked.catalog.admin;

import com.bookmarked.catalog.Author;
import com.bookmarked.catalog.Book;
import com.bookmarked.catalog.BookFormat;
import com.bookmarked.catalog.BookVariant;
import com.bookmarked.catalog.CatalogText;
import com.bookmarked.catalog.Category;
import com.bookmarked.catalog.StockStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Staff-facing catalog requests and responses. Unlike the public DTOs these
 * include exact stock and inactive variants.
 */
public final class AdminCatalogDtos {

    private AdminCatalogDtos() {
    }

    // ------------------------------------------------------------------
    // Books
    // ------------------------------------------------------------------

    public record VariantRequest(
            @NotNull(message = "Choose a format") BookFormat format,
            @NotBlank(message = "ISBN is required") @Size(max = 20) String isbn,
            @Min(value = 1, message = "Pages must be at least 1") @Max(20000) Integer pages,
            @NotNull(message = "MRP is required") @DecimalMin(value = "1.00", message = "MRP must be at least ₹1")
            @DecimalMax("999999.99") @Digits(integer = 6, fraction = 2) BigDecimal mrp,
            @NotNull(message = "Selling price is required")
            @DecimalMin(value = "1.00", message = "Selling price must be at least ₹1")
            @DecimalMax("999999.99") @Digits(integer = 6, fraction = 2) BigDecimal sellingPrice,
            @Min(value = 0, message = "Stock cannot be negative") @Max(100000) Integer stock,
            Boolean active) {
    }

    public record BookRequest(
            @NotBlank(message = "Title is required") @Size(max = 255) String title,
            @Size(max = 250) @Pattern(regexp = "^$|" + CatalogText.SLUG_REGEX,
                    message = "Slug may contain lowercase letters, digits and single hyphens") String slug,
            @Size(max = 10000) String description,
            @Size(max = 40) String language,
            @Size(max = 120) String publisher,
            @Min(value = 1450, message = "Enter a valid year") @Max(value = 2100, message = "Enter a valid year")
            Integer publishedYear,
            @Size(max = 500) @Pattern(regexp = "^$|^https://\\S+$", message = "Cover image must be an https:// URL")
            String coverImageUrl,
            @NotEmpty(message = "Add at least one author") @Size(max = 10)
            List<@NotBlank @Size(max = 120) String> authors,
            @NotEmpty(message = "Pick at least one category") @Size(max = 10) List<@NotNull Long> categoryIds,
            Boolean featured,
            Boolean active,
            @NotEmpty(message = "Add at least one format") @Size(max = 3) List<@Valid VariantRequest> variants) {
    }

    public record ActiveRequest(@NotNull Boolean active) {
    }

    public record FeaturedRequest(@NotNull Boolean featured) {
    }

    public record AdminVariant(Long id, BookFormat format, String isbn, Integer pages, BigDecimal mrp,
                               BigDecimal sellingPrice, int discountPercent, Integer stock, StockStatus stockStatus,
                               boolean active) {
        public static AdminVariant from(BookVariant v) {
            return new AdminVariant(v.getId(), v.getFormat(), v.getIsbn(), v.getPages(), v.getMrp(),
                    v.getSellingPrice(), v.discountPercent(), v.getStock(), v.stockStatus(), v.isActive());
        }
    }

    public record AdminAuthor(Long id, String name) {
        public static AdminAuthor from(Author a) {
            return new AdminAuthor(a.getId(), a.getName());
        }
    }

    /** Row in the admin book table. */
    public record AdminBookRow(Long id, String slug, String title, List<String> authors, String coverImageUrl,
                               BigDecimal fromPrice, boolean featured, boolean active, List<AdminVariant> variants,
                               Instant updatedAt) {
        public static AdminBookRow from(Book b) {
            return new AdminBookRow(b.getId(), b.getSlug(), b.getTitle(), b.authorNames(), b.getCoverImageUrl(),
                    b.getFromPrice(), b.isFeatured(), b.isActive(), sortedVariants(b), b.getUpdatedAt());
        }
    }

    /** Everything the edit form needs. */
    public record AdminBookDetail(Long id, String slug, String title, String description, String language,
                                  String publisher, Integer publishedYear, String coverImageUrl, boolean featured,
                                  boolean active, List<AdminAuthor> authors, List<Long> categoryIds,
                                  List<AdminVariant> variants, BigDecimal avgRating, int ratingCount,
                                  Instant createdAt, Instant updatedAt) {
        public static AdminBookDetail from(Book b) {
            return new AdminBookDetail(b.getId(), b.getSlug(), b.getTitle(), b.getDescription(), b.getLanguage(),
                    b.getPublisher(), b.getPublishedYear(), b.getCoverImageUrl(), b.isFeatured(), b.isActive(),
                    b.getAuthors().stream().map(AdminAuthor::from).toList(),
                    b.getCategories().stream().map(Category::getId).sorted().toList(),
                    sortedVariants(b), b.getAvgRating(), b.getRatingCount(), b.getCreatedAt(), b.getUpdatedAt());
        }
    }

    private static List<AdminVariant> sortedVariants(Book b) {
        return b.getVariants().stream()
                .sorted(Comparator.comparing(BookVariant::getFormat))
                .map(AdminVariant::from)
                .toList();
    }

    // ------------------------------------------------------------------
    // Categories
    // ------------------------------------------------------------------

    public record CategoryRequest(
            @NotBlank(message = "Name is required") @Size(max = 80) String name,
            @Size(max = 100) @Pattern(regexp = "^$|" + CatalogText.SLUG_REGEX,
                    message = "Slug may contain lowercase letters, digits and single hyphens") String slug,
            Long parentId) {
    }

    public record AdminCategory(Long id, String name, String slug, Long parentId, boolean active) {
        public static AdminCategory from(Category c) {
            return new AdminCategory(c.getId(), c.getName(), c.getSlug(), c.getParentId(), c.isActive());
        }
    }
}
