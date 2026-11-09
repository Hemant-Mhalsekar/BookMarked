package com.bookmarked.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogTextTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Clean Code | clean-code",
            "Clean Code: A Handbook of Agile Software Craftsmanship | clean-code-a-handbook-of-agile-software-craftsmanship",
            "Zoë's Café | zoes-cafe",
            "  --Hello,   World!--  | hello-world",
            "1984 | 1984"
    })
    void slugify(String title, String slug) {
        assertThat(CatalogText.slugify(title)).isEqualTo(slug).matches(CatalogText.SLUG_REGEX);
    }

    @ParameterizedTest
    @ValueSource(strings = {"978-0-13-235088-4", "9780132350884", "0-306-40615-2", "080442957X"})
    void accepts_valid_isbns(String isbn) {
        assertThat(CatalogText.normalizeIsbn(isbn)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"9780132350885", "978013235088", "0-306-40615-3", "abc", ""})
    void rejects_invalid_isbns(String isbn) {
        assertThat(CatalogText.normalizeIsbn(isbn)).isEmpty();
    }

    @Test
    void normalises_isbn_formatting() {
        assertThat(CatalogText.normalizeIsbn(" 978-0-13-235088-4 ")).contains("9780132350884");
        assertThat(CatalogText.normalizeIsbn("080442957x")).contains("080442957X");
    }

    @Test
    void builds_safe_boolean_fulltext_queries() {
        assertThat(CatalogText.booleanFullTextQuery("Clean cod")).contains("+clean* +cod*");
        // operators and punctuation are stripped; short words are left to LIKE
        assertThat(CatalogText.booleanFullTextQuery("+java -\"go\" (ai)*")).contains("+java*");
        assertThat(CatalogText.booleanFullTextQuery("go ai")).isEmpty();
        assertThat(CatalogText.booleanFullTextQuery("   ")).isEmpty();
    }

    @Test
    void escapes_like_wildcards() {
        assertThat(CatalogText.likeContains("50%_off!")).isEqualTo("%50!%!_off!!%");
    }

    @Test
    void variant_discount_and_stock_status() {
        Book book = new Book();
        BookVariant paperback = new BookVariant(book, BookFormat.PAPERBACK);
        paperback.setMrp(new BigDecimal("899"));
        paperback.setSellingPrice(new BigDecimal("699"));
        paperback.setStock(3);
        assertThat(paperback.discountPercent()).isEqualTo(22);
        assertThat(paperback.stockStatus()).isEqualTo(StockStatus.LOW_STOCK);

        paperback.setStock(0);
        assertThat(paperback.stockStatus()).isEqualTo(StockStatus.OUT_OF_STOCK);
        assertThat(paperback.isPurchasable()).isFalse();

        BookVariant ebook = new BookVariant(book, BookFormat.EBOOK);
        ebook.setMrp(new BigDecimal("599"));
        ebook.setSellingPrice(new BigDecimal("599"));
        assertThat(ebook.discountPercent()).isZero();
        assertThat(ebook.stockStatus()).isEqualTo(StockStatus.DIGITAL);

        book.getVariants().add(paperback);
        book.getVariants().add(ebook);
        book.refreshFromPrice();
        assertThat(book.getFromPrice()).isEqualByComparingTo("599");
    }
}
