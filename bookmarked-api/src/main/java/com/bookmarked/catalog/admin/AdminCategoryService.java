package com.bookmarked.catalog.admin;

import com.bookmarked.catalog.CatalogService;
import com.bookmarked.catalog.CatalogText;
import com.bookmarked.catalog.Category;
import com.bookmarked.catalog.CategoryRepository;
import com.bookmarked.catalog.admin.AdminCatalogDtos.AdminCategory;
import com.bookmarked.catalog.admin.AdminCatalogDtos.CategoryRequest;
import com.bookmarked.common.ApiException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Two-level categories (top level + one level of subcategories). Categories are
 * never deleted, only deactivated, because books keep pointing at them.
 */
@Service
public class AdminCategoryService {

    private final CategoryRepository categories;

    public AdminCategoryService(CategoryRepository categories) {
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public List<AdminCategory> list() {
        return categories.findAllByOrderByNameAsc().stream().map(AdminCategory::from).toList();
    }

    @Transactional
    @CacheEvict(cacheNames = CatalogService.CATEGORY_CACHE, allEntries = true)
    public AdminCategory create(CategoryRequest req) {
        Category c = new Category();
        apply(c, req);
        return AdminCategory.from(categories.save(c));
    }

    @Transactional
    @CacheEvict(cacheNames = CatalogService.CATEGORY_CACHE, allEntries = true)
    public AdminCategory update(Long id, CategoryRequest req) {
        Category c = load(id);
        apply(c, req);
        return AdminCategory.from(c);
    }

    @Transactional
    @CacheEvict(cacheNames = CatalogService.CATEGORY_CACHE, allEntries = true)
    public AdminCategory setActive(Long id, boolean active) {
        Category c = load(id);
        c.setActive(active);
        return AdminCategory.from(c);
    }

    private void apply(Category c, CategoryRequest req) {
        String name = req.name().trim();
        String slug = StringUtils.hasText(req.slug()) ? req.slug().trim() : CatalogText.slugify(name);
        if (slug.isEmpty()) {
            throw ApiException.badRequest("INVALID_SLUG", "Enter a slug for this category.");
        }
        boolean taken = c.getId() == null ? categories.existsBySlug(slug) : categories.existsBySlugAndIdNot(slug, c.getId());
        if (taken) {
            throw ApiException.conflict("SLUG_TAKEN", "Another category already uses this slug.");
        }

        Category parent = null;
        if (req.parentId() != null) {
            if (req.parentId().equals(c.getId())) {
                throw ApiException.badRequest("INVALID_PARENT", "A category cannot be its own parent.");
            }
            parent = load(req.parentId());
            if (parent.getParent() != null) {
                throw ApiException.badRequest("INVALID_PARENT", "Subcategories can only sit under a top-level category.");
            }
            if (c.getId() != null && categories.findByParentIdIn(List.of(c.getId())).stream().findAny().isPresent()) {
                throw ApiException.badRequest("INVALID_PARENT",
                        "This category has subcategories, so it must stay top-level.");
            }
        }
        c.setName(name);
        c.setSlug(slug);
        c.setParent(parent);
    }

    private Category load(Long id) {
        return categories.findById(id)
                .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found."));
    }
}
