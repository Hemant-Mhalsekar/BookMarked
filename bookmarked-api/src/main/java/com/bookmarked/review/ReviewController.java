package com.bookmarked.review;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading reviews is public (GET /api/books/** is open); writing, voting and
 * reporting need a customer login; moderation needs REVIEW_MODERATE.
 */
@RestController
@Tag(name = "Reviews", description = "Buyers-only reviews, helpful votes, reports and moderation")
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @Operation(summary = "Visible reviews of a book (sort: HELPFUL, NEWEST, HIGHEST, LOWEST; stars: 1–5)")
    @GetMapping("/api/books/{slug}/reviews")
    public PageResponse<ReviewService.ReviewView> list(@PathVariable String slug,
                                                       @RequestParam(defaultValue = "HELPFUL") ReviewService.Sort sort,
                                                       @RequestParam(required = false) Integer stars,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "10") int size,
                                                       @AuthenticationPrincipal AuthUser caller) {
        return reviews.list(slug, sort, stars, caller, page, size);
    }

    @Operation(summary = "Average, star distribution, and whether I can review")
    @GetMapping("/api/books/{slug}/reviews/summary")
    public ReviewService.Summary summary(@PathVariable String slug, @AuthenticationPrincipal AuthUser caller) {
        return reviews.summary(slug, caller);
    }

    @Operation(summary = "Review a book I bought")
    @PostMapping("/api/books/{slug}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewService.ReviewView create(@AuthenticationPrincipal AuthUser caller, @PathVariable String slug,
                                           @Valid @RequestBody ReviewService.ReviewRequest req) {
        return reviews.create(caller, slug, req);
    }

    @Operation(summary = "Edit my review")
    @PutMapping("/api/reviews/{id}")
    public ReviewService.ReviewView update(@AuthenticationPrincipal AuthUser caller, @PathVariable long id,
                                           @Valid @RequestBody ReviewService.ReviewRequest req) {
        return reviews.update(caller, id, req);
    }

    @Operation(summary = "Delete my review")
    @DeleteMapping("/api/reviews/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser caller, @PathVariable long id) {
        reviews.delete(caller, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Mark a review helpful")
    @PutMapping("/api/reviews/{id}/helpful")
    public ReviewService.ReviewView helpful(@AuthenticationPrincipal AuthUser caller, @PathVariable long id) {
        return reviews.helpful(caller, id, true);
    }

    @Operation(summary = "Take back my helpful vote")
    @DeleteMapping("/api/reviews/{id}/helpful")
    public ReviewService.ReviewView unhelpful(@AuthenticationPrincipal AuthUser caller, @PathVariable long id) {
        return reviews.helpful(caller, id, false);
    }

    @Operation(summary = "Report a review (three reports hide it until a moderator decides)")
    @PostMapping("/api/reviews/{id}/report")
    public ResponseEntity<Void> report(@AuthenticationPrincipal AuthUser caller, @PathVariable long id,
                                       @Valid @RequestBody ReviewService.ReportRequest req) {
        reviews.report(caller, id, req.reason());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "My reviews, and delivered books still waiting for one")
    @GetMapping("/api/account/reviews")
    public ReviewService.Mine mine(@AuthenticationPrincipal AuthUser caller) {
        return reviews.mine(caller);
    }

    // ---- moderation ----

    @Operation(summary = "Reviews needing attention (filter: ATTENTION, HIDDEN, REMOVED, ALL)")
    @GetMapping("/api/admin/reviews")
    @PreAuthorize("hasAuthority('REVIEW_MODERATE')")
    public PageResponse<ReviewService.ModerationView> queue(@RequestParam(defaultValue = "ATTENTION") String filter,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return reviews.moderationQueue(filter, page, size);
    }

    @Operation(summary = "Put a review back on the book page")
    @PostMapping("/api/admin/reviews/{id}/restore")
    @PreAuthorize("hasAuthority('REVIEW_MODERATE')")
    public ResponseEntity<Void> restore(@PathVariable long id) {
        reviews.restore(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Remove a review for good")
    @PostMapping("/api/admin/reviews/{id}/remove")
    @PreAuthorize("hasAuthority('REVIEW_MODERATE')")
    public ResponseEntity<Void> remove(@PathVariable long id) {
        reviews.remove(id);
        return ResponseEntity.noContent().build();
    }
}
