-- Step 7: review sorting and moderation queues
CREATE INDEX idx_reviews_book_helpful ON reviews (book_id, status, helpful_count, created_at);
CREATE INDEX idx_reviews_status ON reviews (status, report_count, updated_at);
CREATE INDEX idx_reviews_user ON reviews (user_id, created_at);
