-- Catalog additions for Step 2.

-- Lowest active selling price, kept in sync by the application whenever variants
-- change. Lets listings sort and filter by price without a subquery.
ALTER TABLE books ADD COLUMN from_price DECIMAL(10, 2) NULL AFTER cover_image_url;

CREATE INDEX idx_books_listing ON books (active, created_at);
CREATE INDEX idx_books_price ON books (active, from_price);
CREATE INDEX idx_books_rating ON books (active, avg_rating);
CREATE INDEX idx_authors_name ON authors (name);

-- Starting categories (spec section 5) with a few subcategories.
INSERT INTO categories (name, slug, parent_id, active) VALUES
    ('Fiction', 'fiction', NULL, TRUE),
    ('Non-fiction', 'non-fiction', NULL, TRUE),
    ('Programming', 'programming', NULL, TRUE),
    ('Self-help', 'self-help', NULL, TRUE),
    ('Science', 'science', NULL, TRUE),
    ('History', 'history', NULL, TRUE);

INSERT INTO categories (name, slug, parent_id, active)
SELECT 'Java', 'java', id, TRUE FROM categories WHERE slug = 'programming';
INSERT INTO categories (name, slug, parent_id, active)
SELECT 'Software Engineering', 'software-engineering', id, TRUE FROM categories WHERE slug = 'programming';
INSERT INTO categories (name, slug, parent_id, active)
SELECT 'Psychology', 'psychology', id, TRUE FROM categories WHERE slug = 'non-fiction';
INSERT INTO categories (name, slug, parent_id, active)
SELECT 'Biography', 'biography', id, TRUE FROM categories WHERE slug = 'non-fiction';
INSERT INTO categories (name, slug, parent_id, active)
SELECT 'Fantasy', 'fantasy', id, TRUE FROM categories WHERE slug = 'fiction';
INSERT INTO categories (name, slug, parent_id, active)
SELECT 'Mystery & Thriller', 'mystery-thriller', id, TRUE FROM categories WHERE slug = 'fiction';
