package com.bookmarked.library;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.common.ClientInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/library")
@Tag(name = "My Library", description = "Owned e-books, reading progress, read and download links")
@SecurityRequirement(name = "bearerAuth")
public class LibraryController {

    private final LibraryService library;

    public LibraryController(LibraryService library) {
        this.library = library;
    }

    @Operation(summary = "My e-books, most recently read first")
    @GetMapping
    public List<LibraryService.LibraryBook> list(@AuthenticationPrincipal AuthUser caller) {
        return library.list(caller);
    }

    @Operation(summary = "Variant ids of the e-books I own (to show \"Read now\")")
    @GetMapping("/ids")
    public List<Long> ids(@AuthenticationPrincipal AuthUser caller) {
        return library.ownedIds(caller);
    }

    @Operation(summary = "One of my e-books, with my reading position")
    @GetMapping("/{variantId}")
    public LibraryService.LibraryBook get(@AuthenticationPrincipal AuthUser caller, @PathVariable long variantId) {
        return library.get(caller, variantId);
    }

    @Operation(summary = "Save my reading position")
    @PutMapping("/{variantId}/progress")
    public LibraryService.Progress progress(@AuthenticationPrincipal AuthUser caller, @PathVariable long variantId,
                                            @Valid @RequestBody LibraryService.ProgressRequest req) {
        return library.saveProgress(caller, variantId, req);
    }

    @Operation(summary = "A 5-minute link to read the book in the browser")
    @PostMapping("/{variantId}/read-link")
    public LibraryService.FileLink readLink(@AuthenticationPrincipal AuthUser caller, @PathVariable long variantId) {
        return library.readLink(caller, variantId);
    }

    @Operation(summary = "A single-use link to download a watermarked copy (5 per book)")
    @PostMapping("/{variantId}/download-link")
    public LibraryService.FileLink downloadLink(@AuthenticationPrincipal AuthUser caller, @PathVariable long variantId,
                                                HttpServletRequest request) {
        return library.downloadLink(caller, variantId, ClientInfo.from(request).ipAddress());
    }
}
