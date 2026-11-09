package com.bookmarked.library;

import com.bookmarked.common.ApiException;
import com.bookmarked.storage.FileStorage;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;

/**
 * Serves the bytes behind a file link (and public samples). Read links stream the
 * stored PDF; download links are single-use and stream a copy watermarked with
 * the buyer's name and email.
 */
@Service
public class FileDeliveryService {

    public record Opened(String fileName, boolean attachment, Long length, Body body) {
    }

    @FunctionalInterface
    public interface Body {
        void writeTo(OutputStream out) throws IOException;
    }

    private record Owner(String name, String email, String title, String orderNumber, String key) {
    }

    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    private final FileLinks links;
    private final PdfTools pdf;
    private final Clock clock;

    public FileDeliveryService(JdbcTemplate jdbc, FileStorage storage, FileLinks links, PdfTools pdf, Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.links = links;
        this.pdf = pdf;
        this.clock = clock;
    }

    @Transactional
    public Opened open(String token) {
        FileLinks.Claims claims = links.verify(token);
        // ownership is checked again: a refund or account change after the link was made still counts
        List<Owner> owners = jdbc.query("""
                select u.full_name, u.email, b.title, o.order_number, ef.storage_key
                from user_library ul
                join users u on u.id = ul.user_id
                join book_variants bv on bv.id = ul.variant_id
                join books b on b.id = bv.book_id
                join orders o on o.id = ul.order_id
                join ebook_files ef on ef.variant_id = ul.variant_id
                where ul.user_id = ? and ul.variant_id = ? and u.enabled = true
                """, (rs, i) -> new Owner(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5)), claims.userId(), claims.variantId());
        if (owners.isEmpty()) {
            throw ApiException.forbidden("LINK_INVALID", "This link isn't valid.");
        }
        Owner owner = owners.get(0);
        String fileName = safeName(owner.title()) + ".pdf";

        if (claims.purpose() == FileLinks.Purpose.READ) {
            long size = size(owner.key());
            return new Opened(fileName, false, size, out -> {
                try (InputStream in = storage.open(owner.key())) {
                    in.transferTo(out);
                }
            });
        }

        int used = jdbc.update("update download_logs set used_at = ? where token_id = ? and used_at is null",
                Timestamp.from(clock.instant()), claims.nonce());
        if (used == 0) {
            throw new ApiException(HttpStatus.GONE, "LINK_USED",
                    "This download link has already been used. Start a new download from My Library.");
        }
        return new Opened(fileName, true, null, out -> {
            try (InputStream in = storage.open(owner.key())) {
                pdf.watermark(in, owner.name(), owner.email(), owner.orderNumber(), out);
            }
        });
    }

    /** Public sample of an active e-book. */
    @Transactional(readOnly = true)
    public Opened sample(long variantId) {
        List<String[]> rows = jdbc.query("""
                select ef.sample_storage_key, b.title from ebook_files ef
                join book_variants bv on bv.id = ef.variant_id
                join books b on b.id = bv.book_id
                where ef.variant_id = ? and bv.active = true and b.active = true and ef.sample_storage_key is not null
                """, (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, variantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("SAMPLE_NOT_FOUND", "There's no sample for this book yet.");
        }
        String key = rows.get(0)[0];
        return new Opened(safeName(rows.get(0)[1]) + " - sample.pdf", false, size(key), out -> {
            try (InputStream in = storage.open(key)) {
                in.transferTo(out);
            }
        });
    }

    private long size(String key) {
        try {
            return storage.size(key);
        } catch (IOException e) {
            throw ApiException.notFound("FILE_MISSING", "The file for this book is missing. Please contact support.");
        }
    }

    /** A file name without anything that could break the Content-Disposition header. */
    static String safeName(String title) {
        String s = title == null ? "book" : title.replaceAll("[^A-Za-z0-9 ._-]", "").strip();
        if (s.isEmpty()) {
            s = "book";
        }
        return s.length() > 80 ? s.substring(0, 80).strip() : s;
    }
}
