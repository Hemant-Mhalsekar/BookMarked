package com.bookmarked.catalog;

import com.bookmarked.catalog.dto.CatalogDtos.BookCard;
import com.bookmarked.catalog.dto.CatalogDtos.BookDetail;
import com.bookmarked.catalog.dto.CatalogDtos.CategoryNode;
import com.bookmarked.catalog.dto.CatalogDtos.RelatedBooks;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.PageResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only catalog for customers: search, suggestions, book details, category tree.
 */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    public static final String CATEGORY_CACHE = "categories";
    static final int RELATED_LIMIT = 6;
    public static final int MAX_BY_IDS = 24;

    private final BookRepository books;
    private final CategoryRepository categories;

    public CatalogService(BookRepository books, CategoryRepository categories) {
        this.books = books;
        this.categories = categories;
    }

    public PageResponse<BookCard> search(BookSearchCriteria criteria) {
        Set<Long> categoryIds = criteria.categorySlugs().isEmpty() ? null : expandCategories(criteria.categorySlugs());
        Page<Book> page = books.findAll(BookSpecifications.publicSearch(criteria, categoryIds), criteria.pageable());
        return PageResponse.of(page, BookCard::from);
    }

    /** Up to 6 titles for the search-as-you-type dropdown. */
    public List<String> suggest(String q) {
        if (q == null || q.trim().length() < 2) {
            return List.of();
        }
        String term = q.trim();
        if (term.length() > 60) {
            term = term.substring(0, 60);
        }
        return books.suggestTitles(term, PageRequest.of(0, 6));
    }

    public BookDetail bySlug(String slug) {
        return books.findBySlugAndActiveTrue(slug)
                .filter(b -> !b.activeVariants().isEmpty())
                .map(BookDetail::from)
                .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found."));
    }

    public RelatedBooks related(String slug) {
        Book book = books.findBySlugAndActiveTrue(slug)
                .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found."));
        List<Long> authorIds = book.getAuthors().stream().map(Author::getId).toList();
        List<Book> byAuthor = authorIds.isEmpty() ? List.of()
                : books.findByAuthorsExcluding(authorIds, book.getId(), PageRequest.of(0, RELATED_LIMIT));

        Set<Long> exclude = new HashSet<>();
        exclude.add(book.getId());
        byAuthor.forEach(b -> exclude.add(b.getId()));
        List<Long> categoryIds = book.getCategories().stream().map(Category::getId).toList();
        List<Book> similar = categoryIds.isEmpty() ? List.of()
                : books.findInCategoriesExcluding(categoryIds, exclude, PageRequest.of(0, RELATED_LIMIT));

        return new RelatedBooks(byAuthor.stream().map(BookCard::from).toList(),
                similar.stream().map(BookCard::from).toList());
    }

    /** Cards for the given ids, in the given order, skipping hidden or unknown books. */
    public List<BookCard> byIds(List<Long> ids) {
        List<Long> wanted = ids.stream().distinct().limit(MAX_BY_IDS).toList();
        Map<Long, Book> found = new HashMap<>();
        books.findAllById(wanted).forEach(b -> found.put(b.getId(), b));
        return wanted.stream()
                .map(found::get)
                .filter(b -> b != null && b.isActive() && b.getFromPrice() != null)
                .map(BookCard::from)
                .toList();
    }

    /** Active categories as a two-level tree, alphabetical. Cached; admin changes evict it. */
    @Cacheable(CATEGORY_CACHE)
    public List<CategoryNode> categoryTree() {
        List<Category> all = categories.findAllByOrderByNameAsc().stream().filter(Category::isActive).toList();
        Map<Long, List<CategoryNode>> childrenByParent = new LinkedHashMap<>();
        for (Category c : all) {
            if (c.getParentId() != null) {
                childrenByParent.computeIfAbsent(c.getParentId(), k -> new ArrayList<>())
                        .add(new CategoryNode(c.getId(), c.getName(), c.getSlug(), List.of()));
            }
        }
        return all.stream()
                .filter(c -> c.getParentId() == null)
                .map(c -> new CategoryNode(c.getId(), c.getName(), c.getSlug(),
                        childrenByParent.getOrDefault(c.getId(), List.of()).stream()
                                .sorted(Comparator.comparing(CategoryNode::name)).toList()))
                .toList();
    }

    /** Requested category ids plus all their (active) descendants. Unknown slugs match nothing. */
    Set<Long> expandCategories(List<String> slugs) {
        Set<Long> ids = new HashSet<>();
        List<Long> frontier = categories.findBySlugIn(slugs).stream()
                .filter(Category::isActive).map(Category::getId).toList();
        while (!frontier.isEmpty()) {
            List<Long> fresh = frontier.stream().filter(ids::add).toList();
            frontier = fresh.isEmpty() ? List.of()
                    : categories.findByParent_IdIn(fresh).stream()
                    .filter(Category::isActive).map(Category::getId).toList();
        }
        return ids;
    }
}
