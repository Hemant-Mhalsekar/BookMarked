package com.bookmarked.library;

import com.bookmarked.catalog.BookFormat;
import com.bookmarked.common.ApiException;
import com.bookmarked.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Staff uploads of e-book PDFs. The full book is checked (really a PDF, not
 * encrypted, has pages), stored under a random key, and a sample of the first
 * pages is cut from it automatically; staff can replace the sample with their own.
 * Old files are deleted only after the database change commits.
 */
@Service
public class EbookFileService {

    private static final Logger log = LoggerFactory.getLogger(EbookFileService.class);

    public record EbookFileInfo(Long variantId, boolean uploaded, Long fileSize, Integer pages, boolean hasSample,
                                Integer samplePages, Instant uploadedAt) {
    }

    private record Stored(String key, String sampleKey) {
    }

    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    private final PdfTools pdf;
    private final Clock clock;

    public EbookFileService(JdbcTemplate jdbc, FileStorage storage, PdfTools pdf, Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.pdf = pdf;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public EbookFileInfo info(long variantId) {
        requireEbookVariant(variantId);
        List<EbookFileInfo> rows = jdbc.query("""
                select file_size, page_count, sample_storage_key is not null, sample_pages, uploaded_at
                from ebook_files where variant_id = ?
                """, (rs, i) -> new EbookFileInfo(variantId, true, rs.getLong(1), (Integer) rs.getObject(2),
                rs.getBoolean(3), (Integer) rs.getObject(4),
                rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()), variantId);
        return rows.isEmpty() ? new EbookFileInfo(variantId, false, null, null, false, null, null) : rows.get(0);
    }

    /** The full book; also (re)generates the sample unless {@code keepSample} and one exists. */
    @Transactional
    public EbookFileInfo uploadBook(long variantId, MultipartFile file) {
        requireEbookVariant(variantId);
        Path temp = toTempFile(file);
        List<String> written = new ArrayList<>();
        try {
            int pages = pdf.inspect(temp).pages();
            String key = "ebooks/" + UUID.randomUUID() + ".pdf";
            String sampleKey = "samples/" + UUID.randomUUID() + ".pdf";
            int samplePages = PdfTools.samplePages(pages);
            storage.put(key, temp);
            written.add(key);
            storage.put(sampleKey, pdf.firstPages(temp, samplePages));
            written.add(sampleKey);

            Stored old = current(variantId);
            Timestamp now = Timestamp.from(clock.instant());
            jdbc.update("""
                    insert into ebook_files (variant_id, storage_key, sample_storage_key, file_size, page_count, sample_pages, uploaded_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    on duplicate key update storage_key = values(storage_key), sample_storage_key = values(sample_storage_key),
                        file_size = values(file_size), page_count = values(page_count), sample_pages = values(sample_pages),
                        uploaded_at = values(uploaded_at)
                    """, variantId, key, sampleKey, Files.size(temp), pages, samplePages, now);
            jdbc.update("update book_variants set pages = ? where id = ?", pages, variantId);
            if (old != null) {
                afterCommit(() -> {
                    storage.delete(old.key());
                    storage.delete(old.sampleKey());
                });
            }
            onRollback(written);
            log.info("E-book uploaded for variant {}: {} pages, sample {} pages", variantId, pages, samplePages);
            return info(variantId);
        } catch (IOException e) {
            written.forEach(storage::delete);
            throw new UncheckedIOException("Could not store e-book for variant " + variantId, e);
        } catch (RuntimeException e) {
            written.forEach(storage::delete);
            throw e;
        } finally {
            deleteQuietly(temp);
        }
    }

    /** Replaces the automatic sample with one made by staff. */
    @Transactional
    public EbookFileInfo uploadSample(long variantId, MultipartFile file) {
        requireEbookVariant(variantId);
        Stored old = current(variantId);
        if (old == null) {
            throw ApiException.conflict("EBOOK_FILE_REQUIRED", "Upload the full book first.");
        }
        Path temp = toTempFile(file);
        String sampleKey = "samples/" + UUID.randomUUID() + ".pdf";
        try {
            int pages = pdf.inspect(temp).pages();
            storage.put(sampleKey, temp);
            jdbc.update("update ebook_files set sample_storage_key = ?, sample_pages = ? where variant_id = ?",
                    sampleKey, pages, variantId);
            afterCommit(() -> storage.delete(old.sampleKey()));
            onRollback(List.of(sampleKey));
            return info(variantId);
        } catch (IOException e) {
            storage.delete(sampleKey);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            storage.delete(sampleKey);
            throw e;
        } finally {
            deleteQuietly(temp);
        }
    }

    // ------------------------------------------------------------------

    private void requireEbookVariant(long variantId) {
        List<String> format = jdbc.queryForList("select format from book_variants where id = ?", String.class, variantId);
        if (format.isEmpty()) {
            throw ApiException.notFound("VARIANT_NOT_FOUND", "Format not found.");
        }
        if (!BookFormat.EBOOK.name().equals(format.get(0))) {
            throw ApiException.badRequest("NOT_AN_EBOOK", "Files can only be attached to the E-book format.");
        }
    }

    private Stored current(long variantId) {
        List<Stored> rows = jdbc.query("select storage_key, sample_storage_key from ebook_files where variant_id = ?",
                (rs, i) -> new Stored(rs.getString(1), rs.getString(2)), variantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Path toTempFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("FILE_REQUIRED", "Choose a PDF to upload.");
        }
        if (file.getSize() > PdfTools.MAX_BYTES) {
            throw new ApiException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "PDFs can be up to 100 MB.");
        }
        try {
            Path temp = Files.createTempFile("bm-upload-", ".pdf");
            file.transferTo(temp);
            return temp;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the upload", e);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // temp file; the OS cleans up eventually
        }
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        }
    }

    /** If the transaction rolls back after the files were written, remove them again. */
    private void onRollback(List<String> keys) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        keys.forEach(storage::delete);
                    }
                }
            });
        }
    }
}
