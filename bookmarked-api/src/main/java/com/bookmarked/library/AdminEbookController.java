package com.bookmarked.library;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/variants/{variantId}/ebook")
@Tag(name = "Admin: e-book files", description = "Upload the PDF of an E-book format and its sample")
@SecurityRequirement(name = "bearerAuth")
public class AdminEbookController {

    private final EbookFileService files;

    public AdminEbookController(EbookFileService files) {
        this.files = files;
    }

    @Operation(summary = "What's uploaded for this e-book")
    @GetMapping
    @PreAuthorize("hasAuthority('BOOK_READ')")
    public EbookFileService.EbookFileInfo info(@PathVariable long variantId) {
        return files.info(variantId);
    }

    @Operation(summary = "Upload or replace the full PDF (max 100 MB); a sample is cut from it automatically")
    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('BOOK_WRITE')")
    public EbookFileService.EbookFileInfo upload(@PathVariable long variantId, @RequestPart("file") MultipartFile file) {
        return files.uploadBook(variantId, file);
    }

    @Operation(summary = "Replace the automatic sample with your own PDF")
    @PutMapping(path = "/sample", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('BOOK_WRITE')")
    public EbookFileService.EbookFileInfo sample(@PathVariable long variantId, @RequestPart("file") MultipartFile file) {
        return files.uploadSample(variantId, file);
    }
}
