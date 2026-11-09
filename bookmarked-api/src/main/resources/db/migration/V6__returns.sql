-- Step 5: returns queue for staff, and who last handled each return
ALTER TABLE returns ADD COLUMN handled_by BIGINT NULL,
    ADD CONSTRAINT fk_returns_staff FOREIGN KEY (handled_by) REFERENCES users (id);
CREATE INDEX idx_returns_status ON returns (status, requested_at);
