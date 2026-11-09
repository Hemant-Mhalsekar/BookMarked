package com.bookmarked.catalog;

public enum BookFormat {
    PAPERBACK,
    HARDCOVER,
    EBOOK;

    public boolean isPhysical() {
        return this != EBOOK;
    }
}
