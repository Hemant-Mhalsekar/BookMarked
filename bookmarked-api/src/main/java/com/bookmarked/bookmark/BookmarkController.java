package com.bookmarked.bookmark;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.bookmark.BookmarkService.BookmarkView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/bookmarks")
@Tag(name = "Bookmarks", description = "Books saved for later (the wishlist)")
@SecurityRequirement(name = "bearerAuth")
public class BookmarkController {

    private final BookmarkService bookmarks;

    public BookmarkController(BookmarkService bookmarks) {
        this.bookmarks = bookmarks;
    }

    @Operation(summary = "Bookmarked books, newest first, with their formats")
    @GetMapping
    public List<BookmarkView> list(@AuthenticationPrincipal AuthUser caller) {
        return bookmarks.list(caller);
    }

    @Operation(summary = "Ids of bookmarked books, to draw bookmark icons")
    @GetMapping("/ids")
    public List<Long> ids(@AuthenticationPrincipal AuthUser caller) {
        return bookmarks.bookIds(caller);
    }

    @Operation(summary = "Bookmark a book (idempotent)")
    @PutMapping("/{bookId}")
    public ResponseEntity<Void> add(@AuthenticationPrincipal AuthUser caller, @PathVariable Long bookId) {
        bookmarks.add(caller, bookId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Remove a bookmark (idempotent)")
    @DeleteMapping("/{bookId}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal AuthUser caller, @PathVariable Long bookId) {
        bookmarks.remove(caller, bookId);
        return ResponseEntity.noContent().build();
    }
}
