package com.bookmarked.catalog;

import com.bookmarked.catalog.dto.CatalogDtos.BookCard;
import com.bookmarked.catalog.dto.CatalogDtos.BookDetail;
import com.bookmarked.catalog.dto.CatalogDtos.CategoryNode;
import com.bookmarked.catalog.dto.CatalogDtos.RelatedBooks;
import com.bookmarked.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Catalog", description = "Public book browsing and search")
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @Operation(summary = "Search and filter books",
            description = "sort = relevance | price_asc | price_desc | newest | rating; category = comma-separated slugs "
                    + "(subcategories included); format = PAPERBACK | HARDCOVER | EBOOK")
    @GetMapping("/books")
    public PageResponse<BookCard> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) BigDecimal minRating,
            @RequestParam(required = false) Boolean inStock,
            @RequestParam(required = false) Boolean featured,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return catalog.search(BookSearchCriteria.of(q, category, author, format, minPrice, maxPrice, minRating,
                inStock, featured, sort, page, size));
    }

    @Operation(summary = "Title suggestions for the search box (min 2 characters)")
    @GetMapping("/books/suggest")
    public List<String> suggest(@RequestParam(required = false) String q) {
        return catalog.suggest(q);
    }

    @Operation(summary = "Cards for up to 24 book ids, in the given order (used for recently viewed)")
    @GetMapping("/books/by-ids")
    public List<BookCard> byIds(@RequestParam List<Long> ids) {
        return catalog.byIds(ids);
    }

    @Operation(summary = "More by the same author and similar books")
    @GetMapping("/books/{slug}/related")
    public RelatedBooks related(@PathVariable String slug) {
        return catalog.related(slug);
    }

    @Operation(summary = "One book with its formats, authors and categories")
    @GetMapping("/books/{slug}")
    public BookDetail book(@PathVariable String slug) {
        return catalog.bySlug(slug);
    }

    @Operation(summary = "Active categories as a two-level tree")
    @GetMapping("/categories")
    public List<CategoryNode> categories() {
        return catalog.categoryTree();
    }
}
