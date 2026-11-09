package com.bookmarked.catalog.admin;

import com.bookmarked.catalog.Author;
import com.bookmarked.catalog.AuthorRepository;
import com.bookmarked.catalog.Book;
import com.bookmarked.catalog.BookFormat;
import com.bookmarked.catalog.BookRepository;
import com.bookmarked.catalog.BookSpecifications;
import com.bookmarked.catalog.BookVariant;
import com.bookmarked.catalog.BookVariantRepository;
import com.bookmarked.catalog.CatalogText;
import com.bookmarked.catalog.Category;
import com.bookmarked.catalog.CategoryRepository;
import com.bookmarked.catalog.InventoryMovement;
import com.bookmarked.catalog.InventoryMovementRepository;
import com.bookmarked.catalog.admin.AdminCatalogDtos.AdminAuthor;
import com.bookmarked.catalog.admin.AdminCatalogDtos.AdminBookDetail;
import com.bookmarked.catalog.admin.AdminCatalogDtos.AdminBookRow;
import com.bookmarked.catalog.admin.AdminCatalogDtos.BookRequest;
import com.bookmarked.catalog.admin.AdminCatalogDtos.VariantRequest;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.PageResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Staff book management. A book is saved together with its variants in one
 * request; variants are matched by format, and a format left out of an update is
 * deactivated (never deleted, because orders may reference it).
 */
@Service
public class AdminBookService {

    /** Paths under /api/books that must never be used as a slug. */
    private static final Set<String> RESERVED_SLUGS = Set.of("suggest", "new", "search");

    private final BookRepository books;
    private final BookVariantRepository variants;
    private final AuthorRepository authors;
    private final CategoryRepository categories;
    private final InventoryMovementRepository movements;

    public AdminBookService(BookRepository books, BookVariantRepository variants, AuthorRepository authors,
                            CategoryRepository categories, InventoryMovementRepository movements) {
        this.books = books;
        this.variants = variants;
        this.authors = authors;
        this.categories = categories;
        this.movements = movements;
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<AdminBookRow> list(String q, String status, int page, int size) {
        Boolean active = switch (status == null ? "all" : status.toLowerCase()) {
            case "active" -> Boolean.TRUE;
            case "inactive" -> Boolean.FALSE;
            case "all" -> null;
            default -> throw ApiException.badRequest("INVALID_STATUS", "status must be active, inactive or all");
        };
        if (page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be >= 0 and size between 1 and 100");
        }
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        return PageResponse.of(books.findAll(BookSpecifications.adminSearch(q, active), pageable), AdminBookRow::from);
    }

    @Transactional(readOnly = true)
    public AdminBookDetail get(Long id) {
        return AdminBookDetail.from(load(id));
    }

    @Transactional(readOnly = true)
    public List<AdminAuthor> searchAuthors(String q) {
        String term = q == null ? "" : q.trim();
        return authors.findByNameContainingIgnoreCaseOrderByName(term, PageRequest.of(0, 20)).stream()
                .map(AdminAuthor::from).toList();
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    @Transactional
    public AdminBookDetail create(BookRequest req, Long staffId) {
        Map<BookFormat, VariantRequest> byFormat = validateVariants(req.variants());

        Book book = new Book();
        applyDetails(book, req);
        book.setSlug(resolveSlug(req.slug(), req.title(), null));
        book.setActive(req.active() == null || req.active());

        for (VariantRequest vr : byFormat.values()) {
            BookVariant v = new BookVariant(book, vr.format());
            applyVariant(v, vr, null);
            book.getVariants().add(v);
        }
        book.refreshFromPrice();
        Book saved;
        try {
            saved = books.saveAndFlush(book);   // new book: persist cascades to its variants
        } catch (DataIntegrityViolationException e) {
            throw duplicate();
        }

        for (BookVariant v : saved.getVariants()) {
            if (v.getFormat().isPhysical() && v.getStock() != null && v.getStock() > 0) {
                movements.save(new InventoryMovement(v.getId(), v.getStock(), InventoryMovement.Reason.RESTOCK,
                        null, "Initial stock", staffId));
            }
        }
        return AdminBookDetail.from(saved);
    }

    @Transactional
    public AdminBookDetail update(Long id, BookRequest req, Long staffId) {
        Book book = load(id);
        Map<BookFormat, VariantRequest> byFormat = validateVariants(req.variants());

        applyDetails(book, req);
        if (StringUtils.hasText(req.slug()) && !req.slug().equals(book.getSlug())) {
            book.setSlug(resolveSlug(req.slug(), req.title(), book.getId()));
        }
        if (req.active() != null) {
            book.setActive(req.active());
        }

        record StockChange(BookVariant variant, int delta, boolean isNew) {
        }
        List<StockChange> stockChanges = new ArrayList<>();

        for (BookFormat format : BookFormat.values()) {
            VariantRequest vr = byFormat.get(format);
            BookVariant existing = book.variant(format).orElse(null);
            if (vr == null) {
                if (existing != null) {
                    existing.setActive(false);   // removed from the form: hide, keep for order history
                }
                continue;
            }
            if (existing == null) {
                BookVariant v = new BookVariant(book, format);
                applyVariant(v, vr, null);
                book.getVariants().add(v);
                variants.save(v);   // persist explicitly; the book itself is already managed
                if (format.isPhysical() && v.getStock() != null && v.getStock() > 0) {
                    stockChanges.add(new StockChange(v, v.getStock(), true));
                }
            } else {
                int before = existing.getStock() == null ? 0 : existing.getStock();
                applyVariant(existing, vr, existing.getId());
                int after = existing.getStock() == null ? 0 : existing.getStock();
                if (format.isPhysical() && after != before) {
                    stockChanges.add(new StockChange(existing, after - before, false));
                }
            }
        }
        book.refreshFromPrice();
        try {
            books.flush();   // managed entity: changes are written on flush, no merge needed
        } catch (DataIntegrityViolationException e) {
            throw duplicate();
        }

        for (StockChange change : stockChanges) {
            movements.save(new InventoryMovement(change.variant().getId(), change.delta(),
                    change.isNew() ? InventoryMovement.Reason.RESTOCK : InventoryMovement.Reason.ADJUSTMENT,
                    null, change.isNew() ? "Initial stock" : "Edited in book form", staffId));
        }
        return AdminBookDetail.from(book);
    }

    @Transactional
    public AdminBookDetail setActive(Long id, boolean active) {
        Book book = load(id);
        book.setActive(active);
        return AdminBookDetail.from(book);
    }

    @Transactional
    public AdminBookDetail setFeatured(Long id, boolean featured) {
        Book book = load(id);
        book.setFeatured(featured);
        return AdminBookDetail.from(book);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Book load(Long id) {
        return books.findById(id).orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found."));
    }

    /** Lost a race on a unique ISBN or slug after our own checks passed. */
    private static ApiException duplicate() {
        return ApiException.conflict("DUPLICATE_VALUE", "Another book already uses this ISBN or slug.");
    }

    private void applyDetails(Book book, BookRequest req) {
        book.setTitle(req.title().trim());
        book.setDescription(trimToNull(req.description()));
        book.setLanguage(trimToNull(req.language()));
        book.setPublisher(trimToNull(req.publisher()));
        book.setPublishedYear(req.publishedYear());
        book.setCoverImageUrl(trimToNull(req.coverImageUrl()));
        book.setFeatured(Boolean.TRUE.equals(req.featured()));

        book.getAuthors().clear();
        book.getAuthors().addAll(resolveAuthors(req.authors()));

        Set<Long> ids = new LinkedHashSet<>(req.categoryIds());
        List<Category> found = categories.findAllById(ids);
        if (found.size() != ids.size()) {
            throw ApiException.badRequest("UNKNOWN_CATEGORY", "One or more categories do not exist.");
        }
        book.getCategories().clear();
        book.getCategories().addAll(found);
    }

    /** Reuses authors by case-insensitive name; creates the rest. */
    private List<Author> resolveAuthors(List<String> names) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String raw : names) {
            String name = raw.trim().replaceAll("\\s+", " ");
            unique.putIfAbsent(name.toLowerCase(), name);
        }
        List<Author> result = new ArrayList<>();
        for (String name : unique.values()) {
            result.add(authors.findFirstByNameIgnoreCase(name).orElseGet(() -> authors.save(new Author(name))));
        }
        return result;
    }

    /** One entry per format, valid prices, valid and unused ISBNs. */
    private Map<BookFormat, VariantRequest> validateVariants(List<VariantRequest> requested) {
        Map<BookFormat, VariantRequest> byFormat = new LinkedHashMap<>();
        Set<String> isbns = new HashSet<>();
        EnumSet<BookFormat> seen = EnumSet.noneOf(BookFormat.class);
        for (VariantRequest vr : requested) {
            if (!seen.add(vr.format())) {
                throw ApiException.badRequest("DUPLICATE_FORMAT", "Each format can only be added once.");
            }
            if (vr.sellingPrice().compareTo(vr.mrp()) > 0) {
                throw ApiException.badRequest("PRICE_ABOVE_MRP",
                        vr.format() + ": selling price cannot be higher than MRP.");
            }
            String isbn = CatalogText.normalizeIsbn(vr.isbn()).orElseThrow(() ->
                    ApiException.badRequest("INVALID_ISBN", vr.format() + ": enter a valid ISBN-10 or ISBN-13."));
            if (!isbns.add(isbn)) {
                throw ApiException.badRequest("DUPLICATE_ISBN", "Each format needs its own ISBN.");
            }
            if (vr.format().isPhysical() && vr.stock() == null) {
                throw ApiException.badRequest("STOCK_REQUIRED", vr.format() + ": enter the stock quantity.");
            }
            byFormat.put(vr.format(), vr);
        }
        return byFormat;
    }

    private void applyVariant(BookVariant v, VariantRequest vr, Long existingId) {
        String isbn = CatalogText.normalizeIsbn(vr.isbn()).orElseThrow();
        boolean taken = existingId == null ? variants.existsByIsbn(isbn) : variants.existsByIsbnAndIdNot(isbn, existingId);
        if (taken) {
            throw ApiException.conflict("ISBN_TAKEN", "ISBN " + isbn + " is already used by another book.");
        }
        v.setIsbn(isbn);
        v.setPages(vr.pages());
        v.setMrp(vr.mrp());
        v.setSellingPrice(vr.sellingPrice());
        v.setStock(vr.format().isPhysical() ? vr.stock() : null);   // e-books have no stock
        v.setActive(vr.active() == null || vr.active());
    }

    private String resolveSlug(String requested, String title, Long bookId) {
        if (StringUtils.hasText(requested)) {
            String slug = requested.trim();
            if (RESERVED_SLUGS.contains(slug)) {
                throw ApiException.badRequest("SLUG_RESERVED", "This slug is reserved. Choose another.");
            }
            boolean taken = bookId == null ? books.existsBySlug(slug) : books.existsBySlugAndIdNot(slug, bookId);
            if (taken) {
                throw ApiException.conflict("SLUG_TAKEN", "Another book already uses this slug.");
            }
            return slug;
        }
        String base = CatalogText.slugify(title);
        if (base.isEmpty() || RESERVED_SLUGS.contains(base)) {
            base = "book" + (base.isEmpty() ? "" : "-" + base);
        }
        String slug = base;
        for (int n = 2; bookId == null ? books.existsBySlug(slug) : books.existsBySlugAndIdNot(slug, bookId); n++) {
            slug = base + "-" + n;
        }
        return slug;
    }

    private static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
