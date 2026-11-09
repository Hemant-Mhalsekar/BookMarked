package com.bookmarked.library;

import com.bookmarked.common.ApiException;
import com.bookmarked.order.InvoiceService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Everything BookMarked does to PDFs besides invoices: checking uploads, cutting samples, watermarking. */
@Component
public class PdfTools {

    /** Every PDF starts with these bytes; the file name and Content-Type can lie, this can't. */
    private static final byte[] MAGIC = {'%', 'P', 'D', 'F', '-'};
    public static final long MAX_BYTES = 100L * 1024 * 1024;
    public static final int MAX_SAMPLE_PAGES = 10;

    public record Inspection(int pages) {
    }

    /** True when the file begins with %PDF-. */
    public static boolean looksLikePdf(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(MAGIC.length);
            return Arrays.equals(head, MAGIC);
        }
    }

    /** Opens the PDF fully: rejects files that aren't PDFs, are broken, password-protected or empty. */
    public Inspection inspect(Path file) {
        try {
            if (Files.size(file) > MAX_BYTES) {
                throw new ApiException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                        "PDFs can be up to 100 MB.");
            }
            if (!looksLikePdf(file)) {
                throw ApiException.badRequest("NOT_A_PDF", "That file isn't a PDF.");
            }
            try (PDDocument doc = Loader.loadPDF(file.toFile())) {
                if (doc.isEncrypted()) {
                    throw ApiException.badRequest("PDF_ENCRYPTED", "Remove the password from the PDF before uploading it.");
                }
                if (doc.getNumberOfPages() == 0) {
                    throw ApiException.badRequest("PDF_EMPTY", "That PDF has no pages.");
                }
                return new Inspection(doc.getNumberOfPages());
            }
        } catch (IOException e) {
            throw ApiException.badRequest("PDF_UNREADABLE", "That PDF couldn't be read. Is the file damaged?");
        }
    }

    /** How many pages a sample gets: about a tenth of the book, between 1 and 10. */
    public static int samplePages(int totalPages) {
        return Math.max(1, Math.min(MAX_SAMPLE_PAGES, (int) Math.ceil(totalPages / 10.0)));
    }

    /** The first {@code pages} pages as a new PDF. */
    public byte[] firstPages(Path file, int pages) throws IOException {
        try (PDDocument source = Loader.loadPDF(file.toFile()); PDDocument sample = new PDDocument()) {
            int n = Math.min(pages, source.getNumberOfPages());
            for (int i = 0; i < n; i++) {
                sample.importPage(source.getPage(i));
            }
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle(source.getDocumentInformation().getTitle());
            info.setSubject("Sample from BookMarked");
            sample.setDocumentInformation(info);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            sample.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Writes a copy of the PDF with the buyer's name and email on every page (a footer
     * line and a faint diagonal mark) and in the document properties, so a shared copy
     * can be traced back to its buyer.
     */
    public void watermark(InputStream source, String buyerName, String buyerEmail, String orderRef, OutputStream out)
            throws IOException {
        String footer = InvoiceService.clean("Licensed to " + buyerName + " <" + buyerEmail + ">"
                + (orderRef == null ? "" : " - order " + orderRef) + " - BookMarked. Please don't share.");
        String diagonal = InvoiceService.clean(buyerEmail);
        PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

        try (PDDocument doc = Loader.loadPDF(new RandomAccessReadBuffer(source))) {
            PDExtendedGraphicsState faint = new PDExtendedGraphicsState();
            faint.setNonStrokingAlphaConstant(0.08f);
            PDExtendedGraphicsState soft = new PDExtendedGraphicsState();
            soft.setNonStrokingAlphaConstant(0.55f);

            for (PDPage page : doc.getPages()) {
                PDRectangle box = page.getMediaBox();
                try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    // footer
                    float size = Math.max(6f, Math.min(8f, box.getWidth() / 75f));
                    cs.setGraphicsStateParameters(soft);
                    cs.setNonStrokingColor(0.35f, 0.35f, 0.4f);
                    cs.beginText();
                    cs.setFont(font, size);
                    float width = font.getStringWidth(footer) / 1000 * size;
                    cs.newLineAtOffset(box.getLowerLeftX() + Math.max(10, (box.getWidth() - width) / 2), box.getLowerLeftY() + 10);
                    cs.showText(footer);
                    cs.endText();

                    // faint diagonal mark across the middle
                    float big = Math.max(18f, box.getWidth() / 18f);
                    cs.setGraphicsStateParameters(faint);
                    cs.setNonStrokingColor(0.1f, 0.15f, 0.4f);
                    cs.beginText();
                    cs.setFont(font, big);
                    float w = font.getStringWidth(diagonal) / 1000 * big;
                    Matrix m = Matrix.getRotateInstance(Math.toRadians(35),
                            box.getLowerLeftX() + box.getWidth() / 2 - (float) (w / 2 * Math.cos(Math.toRadians(35))),
                            box.getLowerLeftY() + box.getHeight() / 2 - (float) (w / 2 * Math.sin(Math.toRadians(35))));
                    cs.setTextMatrix(m);
                    cs.showText(diagonal);
                    cs.endText();
                }
            }
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setKeywords(InvoiceService.clean("Licensed to " + buyerEmail));
            info.setCustomMetadataValue("BookMarkedLicensee", InvoiceService.clean(buyerName + " <" + buyerEmail + ">"));
            doc.save(out);
        }
    }
}
