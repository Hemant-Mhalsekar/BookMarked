package com.bookmarked.order;

import com.bookmarked.common.ApiException;
import com.bookmarked.order.OrderEnums.FulfillmentStatus;
import com.bookmarked.order.OrderEnums.PaymentMethod;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * A one-page-per-25-lines PDF invoice drawn with PDFBox's built-in Helvetica.
 * Built-in fonts only cover Western European characters, so text is cleaned
 * first and amounts use "Rs." instead of the rupee sign.
 */
@Service
public class InvoiceService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.of("Asia/Kolkata"));
    private static final float MARGIN = 50;
    private static final float[] COLS = {50, 330, 380, 450};   // item, qty, unit price, amount (right edge 545)
    private static final float RIGHT = 545;
    private static final int LINES_PER_PAGE = 25;

    private final PurchaseOrderRepository orders;
    private final RefundService refunds;

    public InvoiceService(PurchaseOrderRepository orders, RefundService refunds) {
        this.orders = orders;
        this.refunds = refunds;
    }

    public record Invoice(String fileName, byte[] pdf) {
    }

    /** For the customer (their own order) or for staff (userId null). */
    @Transactional(readOnly = true)
    public Invoice forOrder(String orderNumber, Long userId) {
        PurchaseOrder order = (userId == null ? orders.findIdByOrderNumber(orderNumber)
                : orders.findIdByOrderNumberAndUserId(orderNumber, userId))
                .flatMap(orders::findById)
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Order not found."));
        if (!OrderQueryService.invoiceAvailable(order)) {
            throw ApiException.conflict("INVOICE_NOT_AVAILABLE", "An invoice is available once the order is confirmed.");
        }
        return new Invoice("BookMarked-Invoice-" + order.getOrderNumber() + ".pdf", render(order));
    }

    byte[] render(PurchaseOrder order) {
        PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        List<OrderItem> items = order.getItems();
        List<RefundService.RefundView> refundRows = refunds.forOrder(order.getId());

        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            int pages = Math.max(1, (items.size() + LINES_PER_PAGE - 1) / LINES_PER_PAGE);
            for (int p = 0; p < pages; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float y = header(cs, regular, bold, order, p + 1, pages);
                    y = tableHeader(cs, bold, y);
                    List<OrderItem> slice = items.subList(p * LINES_PER_PAGE, Math.min(items.size(), (p + 1) * LINES_PER_PAGE));
                    for (OrderItem item : slice) {
                        y = itemRow(cs, regular, item, y);
                    }
                    if (p == pages - 1) {
                        totals(cs, regular, bold, order, refundRows, y - 10);
                    }
                    footer(cs, regular);
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build invoice for " + order.getOrderNumber(), e);
        }
    }

    // ------------------------------------------------------------------

    private float header(PDPageContentStream cs, PDFont regular, PDFont bold, PurchaseOrder o, int page, int pages)
            throws IOException {
        float top = PDRectangle.A4.getHeight() - MARGIN;
        cs.setNonStrokingColor(0.118f, 0.227f, 0.541f);   // brand navy
        text(cs, bold, 22, MARGIN, top - 10, "BookMarked");
        cs.setNonStrokingColor(0.4f, 0.4f, 0.45f);
        text(cs, regular, 9, MARGIN, top - 24, "Books worth coming back to.");
        cs.setNonStrokingColor(0, 0, 0);
        textRight(cs, bold, 16, RIGHT, top - 10, "INVOICE");
        textRight(cs, regular, 9, RIGHT, top - 24, pages > 1 ? "Page " + page + " of " + pages : "");

        float y = top - 60;
        label(cs, bold, regular, MARGIN, y, "Invoice / order no.", o.getOrderNumber());
        label(cs, bold, regular, MARGIN, y - 28, "Order date", DATE.format(o.getCreatedAt()));
        label(cs, bold, regular, MARGIN, y - 56, "Payment",
                o.getPaymentMethod() == PaymentMethod.COD ? "Cash on Delivery" : "Online (Razorpay)");

        if (o.hasShipping()) {
            float x = 320;
            text(cs, bold, 9, x, y, "Ship to");
            float line = y - 13;
            for (String s : new String[]{o.getShipName(), o.getShipLine1(), o.getShipLine2(),
                    o.getShipCity() + ", " + o.getShipState() + " " + o.getShipPincode(), "Phone " + o.getShipPhone()}) {
                if (s != null && !s.isBlank()) {
                    text(cs, regular, 9, x, line, s);
                    line -= 12;
                }
            }
        }
        return y - 90;
    }

    private float tableHeader(PDPageContentStream cs, PDFont bold, float y) throws IOException {
        cs.setNonStrokingColor(0.95f, 0.96f, 0.98f);
        cs.addRect(MARGIN - 4, y - 6, RIGHT - MARGIN + 8, 20);
        cs.fill();
        cs.setNonStrokingColor(0, 0, 0);
        text(cs, bold, 9, COLS[0], y, "Item");
        textRight(cs, bold, 9, COLS[1] + 30, y, "Qty");
        textRight(cs, bold, 9, COLS[2] + 60, y, "Unit price");
        textRight(cs, bold, 9, RIGHT, y, "Amount");
        return y - 24;
    }

    private float itemRow(PDPageContentStream cs, PDFont regular, OrderItem item, float y) throws IOException {
        boolean cancelled = item.getFulfillmentStatus() == FulfillmentStatus.CANCELLED;
        String title = fit(regular, 9, item.getTitleSnapshot(), COLS[1] - COLS[0] - 20);
        text(cs, regular, 9, COLS[0], y, title);
        cs.setNonStrokingColor(0.4f, 0.4f, 0.45f);
        text(cs, regular, 8, COLS[0], y - 11, OrderOperations.formatName(item.getFormat())
                + (cancelled ? "  -  cancelled" : ""));
        cs.setNonStrokingColor(0, 0, 0);
        textRight(cs, regular, 9, COLS[1] + 30, y, String.valueOf(item.getQuantity()));
        textRight(cs, regular, 9, COLS[2] + 60, y, money(item.getUnitPrice()));
        textRight(cs, regular, 9, RIGHT, y, money(item.lineTotal()));
        cs.setStrokingColor(0.9f, 0.9f, 0.92f);
        cs.setLineWidth(0.5f);
        cs.moveTo(MARGIN - 4, y - 17);
        cs.lineTo(RIGHT + 4, y - 17);
        cs.stroke();
        return y - 30;
    }

    private void totals(PDPageContentStream cs, PDFont regular, PDFont bold, PurchaseOrder o,
                        List<RefundService.RefundView> refundRows, float y) throws IOException {
        float labelX = 360;
        BigDecimal itemsTotal = o.getSubtotal().subtract(o.getDiscount());
        y = totalLine(cs, regular, labelX, y, "Items (MRP)", money(o.getSubtotal()));
        if (o.getDiscount().signum() > 0) {
            y = totalLine(cs, regular, labelX, y, "Discount", "- " + money(o.getDiscount()));
        }
        y = totalLine(cs, regular, labelX, y, "Items total", money(itemsTotal));
        y = totalLine(cs, regular, labelX, y, "Delivery", o.getDeliveryFee().signum() > 0 ? money(o.getDeliveryFee()) : "Free");
        if (o.getCodFee().signum() > 0) {
            y = totalLine(cs, regular, labelX, y, "COD fee", money(o.getCodFee()));
        }
        cs.setStrokingColor(0, 0, 0);
        cs.moveTo(labelX, y + 4);
        cs.lineTo(RIGHT, y + 4);
        cs.stroke();
        y -= 10;
        text(cs, bold, 11, labelX, y, "Total");
        textRight(cs, bold, 11, RIGHT, y, money(o.getTotal()));
        y -= 18;
        for (RefundService.RefundView r : refundRows) {
            y = totalLine(cs, regular, labelX, y, "Refund (" + r.status().toLowerCase(Locale.ROOT) + ")",
                    "- " + money(r.amount()));
        }
        cs.setNonStrokingColor(0.4f, 0.4f, 0.45f);
        text(cs, regular, 8, MARGIN, y - 20, "Prices include all applicable taxes.");
        cs.setNonStrokingColor(0, 0, 0);
    }

    private void footer(PDPageContentStream cs, PDFont regular) throws IOException {
        cs.setNonStrokingColor(0.5f, 0.5f, 0.55f);
        text(cs, regular, 8, MARGIN, 40,
                "BookMarked is a portfolio project. This invoice is computer generated and needs no signature.");
        cs.setNonStrokingColor(0, 0, 0);
    }

    private static float totalLine(PDPageContentStream cs, PDFont regular, float x, float y, String label, String value)
            throws IOException {
        text(cs, regular, 9, x, y, label);
        textRight(cs, regular, 9, RIGHT, y, value);
        return y - 14;
    }

    private static void label(PDPageContentStream cs, PDFont bold, PDFont regular, float x, float y, String label,
                              String value) throws IOException {
        text(cs, bold, 9, x, y, label);
        text(cs, regular, 10, x, y - 12, value);
    }

    private static void text(PDPageContentStream cs, PDFont font, float size, float x, float y, String s)
            throws IOException {
        String clean = clean(s);
        if (clean.isEmpty()) {
            return;
        }
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(clean);
        cs.endText();
    }

    private static void textRight(PDPageContentStream cs, PDFont font, float size, float right, float y, String s)
            throws IOException {
        String clean = clean(s);
        text(cs, font, size, right - font.getStringWidth(clean) / 1000 * size, y, clean);
    }

    private static String fit(PDFont font, float size, String s, float maxWidth) throws IOException {
        String clean = clean(s);
        if (font.getStringWidth(clean) / 1000 * size <= maxWidth) {
            return clean;
        }
        while (clean.length() > 1 && font.getStringWidth(clean + "...") / 1000 * size > maxWidth) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean.strip() + "...";
    }

    /** Keeps only characters the built-in fonts can draw. */
    static String clean(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '‘', '’' -> b.append('\'');
                case '“', '”' -> b.append('"');
                case '–', '—' -> b.append('-');
                case '…' -> b.append("...");
                case '₹' -> b.append("Rs.");
                case '×' -> b.append('x');
                default -> {
                    if ((c >= 0x20 && c <= 0x7E) || (c >= 0xA0 && c <= 0xFF)) {
                        b.append(c);
                    } else if (Character.isWhitespace(c)) {
                        b.append(' ');
                    } else {
                        b.append('?');
                    }
                }
            }
        }
        return b.toString();
    }

    static String money(BigDecimal amount) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        return "Rs. " + f.format(amount.setScale(2, RoundingMode.HALF_UP));
    }
}
