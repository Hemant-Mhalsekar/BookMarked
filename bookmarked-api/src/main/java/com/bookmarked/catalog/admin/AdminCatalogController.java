package com.bookmarked.catalog.admin;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.catalog.admin.AdminCatalogDtos.ActiveRequest;
import com.bookmarked.catalog.admin.AdminCatalogDtos.AdminAuthor;
import com.bookmarked.catalog.admin.AdminCatalogDtos.AdminBookDetail;
import com.bookmarked.catalog.admin.AdminCatalogDtos.AdminBookRow;
import com.bookmarked.catalog.admin.AdminCatalogDtos.AdminCategory;
import com.bookmarked.catalog.admin.AdminCatalogDtos.BookRequest;
import com.bookmarked.catalog.admin.AdminCatalogDtos.CategoryRequest;
import com.bookmarked.catalog.admin.AdminCatalogDtos.FeaturedRequest;
import com.bookmarked.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Staff catalog management. The whole /api/admin/** area already requires a staff
 * account; each method additionally checks a specific permission.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin: catalog", description = "Books, formats, authors and categories")
@SecurityRequirement(name = "bearerAuth")
public class AdminCatalogController {

    private final AdminBookService bookService;
    private final AdminCategoryService categoryService;

    public AdminCatalogController(AdminBookService bookService, AdminCategoryService categoryService) {
        this.bookService = bookService;
        this.categoryService = categoryService;
    }

    // ---- books ----

    @Operation(summary = "List books (including inactive) by title, author or ISBN")
    @GetMapping("/books")
    @PreAuthorize("hasAuthority('BOOK_READ')")
    public PageResponse<AdminBookRow> listBooks(@RequestParam(required = false) String q,
                                                @RequestParam(defaultValue = "all") String status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return bookService.list(q, status, page, size);
    }

    @Operation(summary = "One book with all its formats, for the edit form")
    @GetMapping("/books/{id}")
    @PreAuthorize("hasAuthority('BOOK_READ')")
    public AdminBookDetail getBook(@PathVariable Long id) {
        return bookService.get(id);
    }

    @Operation(summary = "Create a book with its formats")
    @PostMapping("/books")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('BOOK_WRITE')")
    public AdminBookDetail createBook(@Valid @RequestBody BookRequest req, @AuthenticationPrincipal AuthUser staff) {
        return bookService.create(req, staff.id());
    }

    @Operation(summary = "Update a book; formats left out are deactivated")
    @PutMapping("/books/{id}")
    @PreAuthorize("hasAuthority('BOOK_WRITE')")
    public AdminBookDetail updateBook(@PathVariable Long id, @Valid @RequestBody BookRequest req,
                                      @AuthenticationPrincipal AuthUser staff) {
        return bookService.update(id, req, staff.id());
    }

    @Operation(summary = "Show or hide a book in the store")
    @PatchMapping("/books/{id}/active")
    @PreAuthorize("hasAuthority('BOOK_WRITE')")
    public AdminBookDetail setBookActive(@PathVariable Long id, @Valid @RequestBody ActiveRequest req) {
        return bookService.setActive(id, req.active());
    }

    @Operation(summary = "Feature a book on the home page")
    @PatchMapping("/books/{id}/featured")
    @PreAuthorize("hasAuthority('BOOK_WRITE')")
    public AdminBookDetail setBookFeatured(@PathVariable Long id, @Valid @RequestBody FeaturedRequest req) {
        return bookService.setFeatured(id, req.featured());
    }

    @Operation(summary = "Author name search for the book form")
    @GetMapping("/authors")
    @PreAuthorize("hasAuthority('BOOK_READ')")
    public List<AdminAuthor> authors(@RequestParam(required = false) String q) {
        return bookService.searchAuthors(q);
    }

    // ---- categories ----

    @Operation(summary = "All categories, including inactive")
    @GetMapping("/categories")
    @PreAuthorize("hasAuthority('BOOK_READ')")
    public List<AdminCategory> categories() {
        return categoryService.list();
    }

    @Operation(summary = "Create a category or subcategory")
    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CATEGORY_WRITE')")
    public AdminCategory createCategory(@Valid @RequestBody CategoryRequest req) {
        return categoryService.create(req);
    }

    @Operation(summary = "Rename or move a category")
    @PutMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('CATEGORY_WRITE')")
    public AdminCategory updateCategory(@PathVariable Long id, @Valid @RequestBody CategoryRequest req) {
        return categoryService.update(id, req);
    }

    @Operation(summary = "Activate or deactivate a category (categories are never deleted)")
    @PatchMapping("/categories/{id}/active")
    @PreAuthorize("hasAuthority('CATEGORY_WRITE')")
    public AdminCategory setCategoryActive(@PathVariable Long id, @Valid @RequestBody ActiveRequest req) {
        return categoryService.setActive(id, req.active());
    }
}
