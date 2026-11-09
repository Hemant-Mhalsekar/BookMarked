package com.bookmarked.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One purchasable format of a book (Paperback, Hardcover or E-book), with its own
 * ISBN, price and stock. E-books have no stock (null).
 */
@Entity
@Table(name = "book_variants")
public class BookVariant {

    /** At or below this many copies a physical variant shows "Only N left". */
    public static final int LOW_STOCK_LEVEL = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id")
    private Book book;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BookFormat format;

    @Column(nullable = false, unique = true, length = 17)
    private String isbn;

    private Integer pages;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal mrp;

    @Column(name = "selling_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal sellingPrice;

    private Integer stock;

    @Column(nullable = false)
    private boolean active = true;

    /** 1 when an e-book sample PDF has been uploaded (read-only, computed by the database). */
    @org.hibernate.annotations.Formula("(select count(*) from ebook_files f where f.variant_id = id and f.sample_storage_key is not null)")
    private int sampleCount;

    public boolean hasSample() {
        return sampleCount > 0;
    }

    protected BookVariant() {
    }

    public BookVariant(Book book, BookFormat format) {
        this.book = book;
        this.format = format;
    }

    public StockStatus stockStatus() {
        if (format == BookFormat.EBOOK) {
            return StockStatus.DIGITAL;
        }
        int s = stock == null ? 0 : stock;
        if (s <= 0) {
            return StockStatus.OUT_OF_STOCK;
        }
        return s <= LOW_STOCK_LEVEL ? StockStatus.LOW_STOCK : StockStatus.IN_STOCK;
    }

    public boolean isPurchasable() {
        return active && stockStatus() != StockStatus.OUT_OF_STOCK;
    }

    /** Whole-number discount, e.g. MRP 899 / price 699 → 22. */
    public int discountPercent() {
        if (mrp == null || sellingPrice == null || mrp.signum() <= 0 || sellingPrice.compareTo(mrp) >= 0) {
            return 0;
        }
        return mrp.subtract(sellingPrice).multiply(BigDecimal.valueOf(100))
                .divide(mrp, 0, RoundingMode.DOWN).intValue();
    }

    public Long getId() {
        return id;
    }

    public Book getBook() {
        return book;
    }

    public BookFormat getFormat() {
        return format;
    }

    public String getIsbn() {
        return isbn;
    }

    public void setIsbn(String isbn) {
        this.isbn = isbn;
    }

    public Integer getPages() {
        return pages;
    }

    public void setPages(Integer pages) {
        this.pages = pages;
    }

    public BigDecimal getMrp() {
        return mrp;
    }

    public void setMrp(BigDecimal mrp) {
        this.mrp = mrp;
    }

    public BigDecimal getSellingPrice() {
        return sellingPrice;
    }

    public void setSellingPrice(BigDecimal sellingPrice) {
        this.sellingPrice = sellingPrice;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
