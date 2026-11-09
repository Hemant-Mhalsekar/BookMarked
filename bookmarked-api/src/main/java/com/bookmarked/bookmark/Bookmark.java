package com.bookmarked.bookmark;

import com.bookmarked.catalog.Book;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A book a customer saved for later ("Bookmarks", the wishlist). Stores the book,
 * not a format: the customer picks a format when moving it to Your Stack.
 */
@Entity
@Table(name = "wishlist_items")
public class Bookmark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id")
    private Book book;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    protected Bookmark() {
    }

    public Bookmark(Long userId, Book book) {
        this.userId = userId;
        this.book = book;
        this.addedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Book getBook() {
        return book;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
