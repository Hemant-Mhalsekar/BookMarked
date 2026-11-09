package com.bookmarked.bookmark;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.catalog.Book;
import com.bookmarked.catalog.BookRepository;
import com.bookmarked.catalog.dto.CatalogDtos.BookCard;
import com.bookmarked.catalog.dto.CatalogDtos.VariantView;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.CustomerOnly;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Bookmarks (wishlist). Adding and removing are idempotent so double taps are harmless. */
@Service
public class BookmarkService {

    static final int MAX_BOOKMARKS = 500;

    private final BookmarkRepository bookmarks;
    private final BookRepository books;

    public BookmarkService(BookmarkRepository bookmarks, BookRepository books) {
        this.bookmarks = bookmarks;
        this.books = books;
    }

    /** A bookmarked book with its formats, so it can be moved to Your Stack directly. */
    public record BookmarkView(BookCard book, List<VariantView> variants, boolean available, Instant addedAt) {
    }

    @Transactional(readOnly = true)
    public List<BookmarkView> list(AuthUser caller) {
        CustomerOnly.require(caller);
        return bookmarks.findByUserIdOrderByAddedAtDesc(caller.id()).stream()
                .map(b -> {
                    Book book = b.getBook();
                    boolean available = book.isActive() && !book.activeVariants().isEmpty();
                    return new BookmarkView(BookCard.from(book),
                            available ? book.activeVariants().stream().map(VariantView::from).toList() : List.of(),
                            available, b.getAddedAt());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Long> bookIds(AuthUser caller) {
        CustomerOnly.require(caller);
        return bookmarks.findBookIds(caller.id());
    }

    @Transactional
    public void add(AuthUser caller, Long bookId) {
        CustomerOnly.require(caller);
        addFor(caller.id(), bookId);
    }

    /** Used by "save for later" in Your Stack too. */
    @Transactional
    public void addFor(Long userId, Long bookId) {
        if (bookmarks.findByUserIdAndBook_Id(userId, bookId).isPresent()) {
            return;
        }
        Book book = books.findById(bookId)
                .filter(Book::isActive)
                .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found."));
        if (bookmarks.countByUserId(userId) >= MAX_BOOKMARKS) {
            throw ApiException.conflict("BOOKMARK_LIMIT", "You can keep up to " + MAX_BOOKMARKS + " bookmarks.");
        }
        bookmarks.save(new Bookmark(userId, book));
    }

    @Transactional
    public void remove(AuthUser caller, Long bookId) {
        CustomerOnly.require(caller);
        bookmarks.findByUserIdAndBook_Id(caller.id(), bookId).ifPresent(bookmarks::delete);
    }
}
