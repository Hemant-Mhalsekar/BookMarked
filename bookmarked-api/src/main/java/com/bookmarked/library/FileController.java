package com.bookmarked.library;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;

/**
 * File bytes. {@code /api/files/{token}} needs no login (the signed token is the
 * permission, like an S3 pre-signed URL); samples are public.
 */
@RestController
@Tag(name = "Files", description = "E-book files behind signed links, and public samples")
public class FileController {

    private final FileDeliveryService files;

    public FileController(FileDeliveryService files) {
        this.files = files;
    }

    @Operation(summary = "Read or download through a signed link from My Library")
    @GetMapping("/api/files/{token}")
    public ResponseEntity<StreamingResponseBody> file(@PathVariable String token) {
        return respond(files.open(token), CacheControl.noStore().cachePrivate());
    }

    @Operation(summary = "Public sample (the first pages) of an e-book")
    @GetMapping("/api/books/variants/{variantId}/sample")
    public ResponseEntity<StreamingResponseBody> sample(@PathVariable long variantId) {
        return respond(files.sample(variantId), CacheControl.maxAge(Duration.ofHours(1)).cachePublic());
    }

    private static ResponseEntity<StreamingResponseBody> respond(FileDeliveryService.Opened f, CacheControl cache) {
        ContentDisposition disposition = (f.attachment() ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(f.fileName()).build();
        ResponseEntity.BodyBuilder res = ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .cacheControl(cache);
        if (f.length() != null) {
            res = res.contentLength(f.length());
        }
        return res.body(out -> f.body().writeTo(out));
    }
}
