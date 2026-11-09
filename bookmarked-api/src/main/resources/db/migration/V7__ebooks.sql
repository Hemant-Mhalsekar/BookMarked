-- Step 6: e-book files, single-use download links

ALTER TABLE ebook_files
    ADD COLUMN page_count   INT         NULL,
    ADD COLUMN sample_pages INT         NULL,
    ADD COLUMN uploaded_at  DATETIME(6) NULL;

-- Each download link is used once; the token id ties the click to its log row
ALTER TABLE download_logs
    ADD COLUMN token_id VARCHAR(40) NULL,
    ADD COLUMN used_at  DATETIME(6) NULL,
    ADD CONSTRAINT uq_download_token UNIQUE (token_id);

CREATE INDEX idx_dl_user_variant ON download_logs (user_id, variant_id, downloaded_at);
