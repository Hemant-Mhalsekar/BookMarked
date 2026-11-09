-- Your Stack (cart): remember the price when a book was added, so customers can
-- be told when it has changed since ("Price of Clean Code changed from ₹699 to ₹649").
ALTER TABLE cart_items ADD COLUMN price_at_add DECIMAL(10, 2) NULL AFTER quantity;

CREATE INDEX idx_wishlist_user ON wishlist_items (user_id, added_at);
CREATE INDEX idx_recently_viewed_user ON recently_viewed (user_id, viewed_at);
