package com.bookmarked.catalog;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.catalog.dto.CatalogDtos.BookCard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/recently-viewed")
@Tag(name = "Recently viewed", description = "Books the logged-in user looked at recently")
@SecurityRequirement(name = "bearerAuth")
public class RecentlyViewedController {

    private final RecentlyViewedService recentlyViewed;

    public RecentlyViewedController(RecentlyViewedService recentlyViewed) {
        this.recentlyViewed = recentlyViewed;
    }

    public record MergeRequest(@NotNull @Size(max = 50) List<@NotNull Long> bookIds) {
    }

    @Operation(summary = "Recently viewed books, newest first")
    @GetMapping
    public List<BookCard> list(@AuthenticationPrincipal AuthUser caller,
                               @RequestParam(defaultValue = "12") int limit) {
        return recentlyViewed.list(caller, limit);
    }

    @Operation(summary = "Record that the user opened a book")
    @PostMapping("/{bookId}")
    public ResponseEntity<Void> record(@AuthenticationPrincipal AuthUser caller, @PathVariable Long bookId) {
        recentlyViewed.record(caller, bookId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Add a guest's browser history after login (newest first)")
    @PostMapping("/merge")
    public ResponseEntity<Void> merge(@AuthenticationPrincipal AuthUser caller, @Valid @RequestBody MergeRequest req) {
        recentlyViewed.merge(caller, req.bookIds());
        return ResponseEntity.noContent().build();
    }
}
