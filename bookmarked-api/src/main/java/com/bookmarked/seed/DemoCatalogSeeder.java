package com.bookmarked.seed;

import com.bookmarked.catalog.Author;
import com.bookmarked.catalog.AuthorRepository;
import com.bookmarked.catalog.Book;
import com.bookmarked.catalog.BookFormat;
import com.bookmarked.catalog.BookRepository;
import com.bookmarked.catalog.BookVariant;
import com.bookmarked.catalog.CategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Fills an empty catalog with sample books so the app has something to show in
 * development and demos. Runs only when app.seed.demo-catalog=true (dev profile).
 * <p>
 * The ISBNs are generated with valid checksums but are NOT the books' real ISBNs,
 * and the prices are illustrative.
 */
@Component
@Order(20)
@ConditionalOnProperty(name = "app.seed.demo-catalog", havingValue = "true")
public class DemoCatalogSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoCatalogSeeder.class);

    private final BookRepository books;
    private final AuthorRepository authors;
    private final CategoryRepository categories;

    public DemoCatalogSeeder(BookRepository books, AuthorRepository authors, CategoryRepository categories) {
        this.books = books;
        this.authors = authors;
        this.categories = categories;
    }

    /** Price per format: {mrp, selling, stock}; null = format not sold. */
    private record Demo(String title, List<String> authors, List<String> categories, String description,
                        String publisher, int year, int pages,
                        int[] paperback, int[] hardcover, int[] ebook, boolean featured) {
    }

    private static final List<Demo> DEMO = List.of(
            new Demo("Clean Code", List.of("Robert C. Martin"), List.of("software-engineering"),
                    "A practical guide to writing code that other people can read, change and trust.",
                    "Prentice Hall", 2008, 464, new int[]{899, 699, 12}, new int[]{1299, 1099, 4}, new int[]{599, 399, 0}, true),
            new Demo("The Pragmatic Programmer", List.of("Andrew Hunt", "David Thomas"), List.of("software-engineering"),
                    "Habits and techniques for a long, productive career in software development.",
                    "Addison-Wesley", 2019, 352, new int[]{799, 649, 20}, null, new int[]{549, 449, 0}, true),
            new Demo("Effective Java", List.of("Joshua Bloch"), List.of("java"),
                    "Item-by-item advice for using the Java language and its libraries well.",
                    "Addison-Wesley", 2018, 412, new int[]{950, 799, 8}, new int[]{1450, 1250, 2}, null, false),
            new Demo("Head First Java", List.of("Kathy Sierra", "Bert Bates"), List.of("java"),
                    "A visual, hands-on introduction to Java and object-oriented programming.",
                    "O'Reilly", 2022, 752, new int[]{1100, 899, 15}, null, new int[]{700, 499, 0}, false),
            new Demo("Refactoring", List.of("Martin Fowler"), List.of("software-engineering"),
                    "How to improve the design of existing code in small, safe steps.",
                    "Addison-Wesley", 2018, 448, new int[]{999, 849, 3}, null, null, false),
            new Demo("Design Patterns", List.of("Erich Gamma", "Richard Helm", "Ralph Johnson", "John Vlissides"),
                    List.of("software-engineering"),
                    "The classic catalogue of reusable solutions to common object-oriented design problems.",
                    "Addison-Wesley", 1994, 416, null, new int[]{1599, 1399, 6}, new int[]{899, 749, 0}, false),
            new Demo("Atomic Habits", List.of("James Clear"), List.of("self-help", "psychology"),
                    "Why small, consistent changes compound into remarkable results, and how to build them.",
                    "Penguin Random House", 2018, 320, new int[]{799, 499, 40}, new int[]{999, 799, 10}, new int[]{399, 299, 0}, true),
            new Demo("Deep Work", List.of("Cal Newport"), List.of("self-help"),
                    "An argument for focused, distraction-free work and a plan for making room for it.",
                    "Grand Central", 2016, 304, new int[]{599, 449, 18}, null, new int[]{349, 249, 0}, false),
            new Demo("The Psychology of Money", List.of("Morgan Housel"), List.of("self-help", "psychology"),
                    "Short stories about how people think about wealth, greed and happiness.",
                    "Jaico Publishing", 2020, 252, new int[]{399, 299, 55}, null, new int[]{299, 199, 0}, true),
            new Demo("Thinking, Fast and Slow", List.of("Daniel Kahneman"), List.of("psychology"),
                    "A tour of the two systems that drive the way we think and the biases that come with them.",
                    "Penguin", 2011, 512, new int[]{699, 549, 9}, null, new int[]{449, 349, 0}, false),
            new Demo("Sapiens", List.of("Yuval Noah Harari"), List.of("history"),
                    "A brief history of humankind, from foragers to the present day.",
                    "Vintage", 2015, 498, new int[]{599, 449, 30}, new int[]{999, 849, 5}, new int[]{399, 299, 0}, true),
            new Demo("A Brief History of Time", List.of("Stephen Hawking"), List.of("science"),
                    "Black holes, the Big Bang and the nature of time, explained for general readers.",
                    "Bantam", 1998, 212, new int[]{499, 379, 14}, null, new int[]{299, 229, 0}, false),
            new Demo("Wings of Fire", List.of("A. P. J. Abdul Kalam", "Arun Tiwari"), List.of("biography"),
                    "The autobiography of the scientist who became India's eleventh President.",
                    "Universities Press", 1999, 196, new int[]{299, 249, 60}, null, new int[]{199, 149, 0}, false),
            new Demo("The Hobbit", List.of("J. R. R. Tolkien"), List.of("fantasy"),
                    "Bilbo Baggins leaves his comfortable home for an unexpected adventure.",
                    "HarperCollins", 1937, 310, new int[]{450, 349, 25}, new int[]{899, 749, 0}, new int[]{299, 199, 0}, false),
            new Demo("Nineteen Eighty-Four", List.of("George Orwell"), List.of("fiction"),
                    "A chilling vision of a society under total surveillance.",
                    "Penguin", 1949, 328, new int[]{299, 199, 70}, null, new int[]{149, 99, 0}, false),
            new Demo("The Murder of Roger Ackroyd", List.of("Agatha Christie"), List.of("mystery-thriller"),
                    "Hercule Poirot investigates a death in a quiet English village.",
                    "HarperCollins", 1926, 288, new int[]{350, 279, 4}, null, null, false));

    @Override
    @Transactional
    public void run(String... args) {
        if (books.count() > 0) {
            return;
        }
        int serial = 0;
        for (Demo d : DEMO) {
            Book book = new Book();
            book.setTitle(d.title());
            book.setSlug(com.bookmarked.catalog.CatalogText.slugify(d.title()));
            book.setDescription(d.description());
            book.setLanguage("English");
            book.setPublisher(d.publisher());
            book.setPublishedYear(d.year());
            book.setFeatured(d.featured());
            for (String name : d.authors()) {
                book.getAuthors().add(authors.findFirstByNameIgnoreCase(name).orElseGet(() -> authors.save(new Author(name))));
            }
            book.getCategories().addAll(categories.findBySlugIn(d.categories()));

            serial++;
            addVariant(book, BookFormat.PAPERBACK, d.paperback(), d.pages(), serial);
            addVariant(book, BookFormat.HARDCOVER, d.hardcover(), d.pages(), serial);
            addVariant(book, BookFormat.EBOOK, d.ebook(), d.pages(), serial);
            book.refreshFromPrice();
            books.save(book);
        }
        log.info("Seeded {} demo books", DEMO.size());
    }

    private static void addVariant(Book book, BookFormat format, int[] price, int pages, int serial) {
        if (price == null) {
            return;
        }
        BookVariant v = new BookVariant(book, format);
        v.setIsbn(demoIsbn(serial, format));
        v.setPages(pages);
        v.setMrp(BigDecimal.valueOf(price[0]));
        v.setSellingPrice(BigDecimal.valueOf(price[1]));
        v.setStock(format.isPhysical() ? price[2] : null);
        book.getVariants().add(v);
    }

    /** 978-0-00-SSS-F-check: a checksum-valid but fictional ISBN-13. */
    static String demoIsbn(int serial, BookFormat format) {
        String base = String.format("97800%06d%d", serial, format.ordinal());
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (base.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return base + (10 - sum % 10) % 10;
    }
}
