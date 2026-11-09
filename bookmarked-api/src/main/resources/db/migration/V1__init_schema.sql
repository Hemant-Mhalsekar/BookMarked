-- =====================================================================
-- BookMarked schema v1 (MySQL 8)
-- Enum-like columns are VARCHAR and validated by Java enums.
-- Money is DECIMAL(10,2) rupees; converted to paise only for Razorpay.
-- =====================================================================

-- ---------------------------------------------------------------------
-- USERS & ACCESS
-- ---------------------------------------------------------------------
CREATE TABLE roles (
    id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(40) NOT NULL UNIQUE
);

CREATE TABLE permissions (
    id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(60) NOT NULL UNIQUE
);

CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES roles (id),
    CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES permissions (id)
);

CREATE TABLE users (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    full_name             VARCHAR(60)  NOT NULL,
    email                 VARCHAR(254) NOT NULL UNIQUE,
    phone                 VARCHAR(10)  NOT NULL UNIQUE,
    password_hash         VARCHAR(100) NOT NULL,
    account_type          VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER',
    role_id               BIGINT       NOT NULL,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    email_verified        BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_attempts INT          NOT NULL DEFAULT 0,
    locked_until          DATETIME(6)     NULL,
    last_login_at         DATETIME(6)     NULL,
    deleted_at            DATETIME(6)     NULL,
    created_at            DATETIME(6)     NOT NULL,
    updated_at            DATETIME(6)     NOT NULL,
    CONSTRAINT fk_users_role FOREIGN KEY (role_id) REFERENCES roles (id)
);

CREATE TABLE addresses (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    full_name  VARCHAR(60)  NOT NULL,
    phone      VARCHAR(10)  NOT NULL,
    line1      VARCHAR(120) NOT NULL,
    line2      VARCHAR(120),
    city       VARCHAR(60)  NOT NULL,
    state      VARCHAR(60)  NOT NULL,
    pincode    VARCHAR(6)   NOT NULL,
    type       VARCHAR(10)  NOT NULL DEFAULT 'HOME',
    is_default BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at DATETIME(6)     NOT NULL,
    CONSTRAINT fk_addresses_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE refresh_tokens (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    token_hash    VARCHAR(64)  NOT NULL UNIQUE,
    device_info   VARCHAR(300),
    ip_address    VARCHAR(45),
    expires_at    DATETIME(6)     NOT NULL,
    last_used_at  DATETIME(6),
    revoked       BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked_at    DATETIME(6),
    revoke_reason VARCHAR(30),
    created_at    DATETIME(6)     NOT NULL,
    CONSTRAINT fk_rt_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_rt_user (user_id, revoked)
);

CREATE TABLE login_history (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT NULL,
    ip_address VARCHAR(45),
    user_agent VARCHAR(300),
    success    BOOLEAN  NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_lh_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_lh_user (user_id, created_at)
);

CREATE TABLE verification_tokens (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    type       VARCHAR(20) NOT NULL,
    new_email  VARCHAR(254),
    expires_at DATETIME(6)    NOT NULL,
    used_at    DATETIME(6),
    created_at DATETIME(6)    NOT NULL,
    CONSTRAINT fk_vt_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_vt_user_type (user_id, type)
);

-- ---------------------------------------------------------------------
-- CATALOG & INVENTORY
-- ---------------------------------------------------------------------
CREATE TABLE categories (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    name      VARCHAR(80)  NOT NULL,
    slug      VARCHAR(100) NOT NULL UNIQUE,
    parent_id BIGINT NULL,
    active    BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories (id)
);

CREATE TABLE authors (
    id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    bio  TEXT
);

CREATE TABLE books (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    title           VARCHAR(255) NOT NULL,
    slug            VARCHAR(280) NOT NULL UNIQUE,
    description     TEXT,
    language        VARCHAR(40),
    publisher       VARCHAR(120),
    published_year  SMALLINT,
    cover_image_url VARCHAR(500),
    avg_rating      DECIMAL(2, 1) NOT NULL DEFAULT 0,
    rating_count    INT           NOT NULL DEFAULT 0,
    featured        BOOLEAN       NOT NULL DEFAULT FALSE,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6)      NOT NULL,
    updated_at      DATETIME(6)      NOT NULL,
    FULLTEXT INDEX ft_books (title, description)
);

CREATE TABLE book_authors (
    book_id   BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    PRIMARY KEY (book_id, author_id),
    CONSTRAINT fk_ba_book FOREIGN KEY (book_id) REFERENCES books (id),
    CONSTRAINT fk_ba_author FOREIGN KEY (author_id) REFERENCES authors (id)
);

CREATE TABLE book_categories (
    book_id     BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    PRIMARY KEY (book_id, category_id),
    CONSTRAINT fk_bc_book FOREIGN KEY (book_id) REFERENCES books (id),
    CONSTRAINT fk_bc_category FOREIGN KEY (category_id) REFERENCES categories (id)
);

CREATE TABLE book_variants (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    book_id       BIGINT         NOT NULL,
    format        VARCHAR(10)    NOT NULL,
    isbn          VARCHAR(17)    NOT NULL UNIQUE,
    pages         INT,
    mrp           DECIMAL(10, 2) NOT NULL,
    selling_price DECIMAL(10, 2) NOT NULL,
    stock         INT NULL,
    active        BOOLEAN        NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_bv_book FOREIGN KEY (book_id) REFERENCES books (id),
    CONSTRAINT uq_book_format UNIQUE (book_id, format),
    CONSTRAINT ck_bv_price CHECK (selling_price <= mrp),
    CONSTRAINT ck_bv_stock CHECK (stock IS NULL OR stock >= 0)
);

CREATE TABLE ebook_files (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    variant_id         BIGINT       NOT NULL UNIQUE,
    storage_key        VARCHAR(300) NOT NULL,
    sample_storage_key VARCHAR(300),
    file_size          BIGINT       NOT NULL,
    CONSTRAINT fk_ef_variant FOREIGN KEY (variant_id) REFERENCES book_variants (id)
);

CREATE TABLE inventory_movements (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    variant_id   BIGINT      NOT NULL,
    change_qty   INT         NOT NULL,
    reason       VARCHAR(20) NOT NULL,
    reference_id VARCHAR(40),
    note         VARCHAR(255),
    performed_by BIGINT NULL,
    created_at   DATETIME(6)    NOT NULL,
    CONSTRAINT fk_im_variant FOREIGN KEY (variant_id) REFERENCES book_variants (id),
    CONSTRAINT fk_im_user FOREIGN KEY (performed_by) REFERENCES users (id),
    INDEX idx_im_variant (variant_id, created_at)
);

CREATE TABLE banners (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    image_url     VARCHAR(500) NOT NULL,
    title         VARCHAR(120),
    link          VARCHAR(500),
    starts_at     DATETIME(6),
    ends_at       DATETIME(6),
    display_order INT     NOT NULL DEFAULT 0,
    active        BOOLEAN NOT NULL DEFAULT TRUE
);

-- ---------------------------------------------------------------------
-- CART, WISHLIST
-- ---------------------------------------------------------------------
CREATE TABLE carts (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT   NOT NULL UNIQUE,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_carts_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE cart_items (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    cart_id    BIGINT   NOT NULL,
    variant_id BIGINT   NOT NULL,
    quantity   INT      NOT NULL,
    added_at   DATETIME(6) NOT NULL,
    CONSTRAINT fk_ci_cart FOREIGN KEY (cart_id) REFERENCES carts (id),
    CONSTRAINT fk_ci_variant FOREIGN KEY (variant_id) REFERENCES book_variants (id),
    CONSTRAINT uq_cart_variant UNIQUE (cart_id, variant_id),
    CONSTRAINT ck_ci_qty CHECK (quantity BETWEEN 1 AND 10)
);

CREATE TABLE wishlist_items (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id  BIGINT   NOT NULL,
    book_id  BIGINT   NOT NULL,
    added_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_wi_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_wi_book FOREIGN KEY (book_id) REFERENCES books (id),
    CONSTRAINT uq_wish UNIQUE (user_id, book_id)
);

CREATE TABLE recently_viewed (
    user_id   BIGINT   NOT NULL,
    book_id   BIGINT   NOT NULL,
    viewed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, book_id),
    CONSTRAINT fk_rv_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_rv_book FOREIGN KEY (book_id) REFERENCES books (id)
);

-- ---------------------------------------------------------------------
-- ORDERS, PAYMENTS, RETURNS
-- ---------------------------------------------------------------------
CREATE TABLE orders (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_number        VARCHAR(24)    NOT NULL UNIQUE,
    user_id             BIGINT         NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    payment_method      VARCHAR(10)    NOT NULL,
    payment_status      VARCHAR(20)    NOT NULL,
    subtotal            DECIMAL(10, 2) NOT NULL,
    discount            DECIMAL(10, 2) NOT NULL DEFAULT 0,
    coupon_discount     DECIMAL(10, 2) NOT NULL DEFAULT 0,
    delivery_fee        DECIMAL(10, 2) NOT NULL DEFAULT 0,
    cod_fee             DECIMAL(10, 2) NOT NULL DEFAULT 0,
    total               DECIMAL(10, 2) NOT NULL,
    ship_name           VARCHAR(60),
    ship_phone          VARCHAR(10),
    ship_line1          VARCHAR(120),
    ship_line2          VARCHAR(120),
    ship_city           VARCHAR(60),
    ship_state          VARCHAR(60),
    ship_pincode        VARCHAR(6),
    razorpay_order_id   VARCHAR(40) UNIQUE,
    razorpay_payment_id VARCHAR(40) UNIQUE,
    idempotency_key     VARCHAR(64) UNIQUE,
    reserved_until      DATETIME(6) NULL,
    paid_at             DATETIME(6) NULL,
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_orders_user (user_id, created_at),
    INDEX idx_orders_expiry (status, reserved_until)
);

CREATE TABLE order_items (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id           BIGINT         NOT NULL,
    variant_id         BIGINT         NOT NULL,
    title_snapshot     VARCHAR(255)   NOT NULL,
    format             VARCHAR(10)    NOT NULL,
    mrp                DECIMAL(10, 2) NOT NULL,
    unit_price         DECIMAL(10, 2) NOT NULL,
    quantity           INT            NOT NULL,
    discount_share     DECIMAL(10, 2) NOT NULL DEFAULT 0,
    fulfillment_status VARCHAR(20)    NOT NULL,
    delivered_at       DATETIME(6) NULL,
    CONSTRAINT fk_oi_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_oi_variant FOREIGN KEY (variant_id) REFERENCES book_variants (id)
);

CREATE TABLE order_status_history (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_item_id BIGINT      NOT NULL,
    status        VARCHAR(30) NOT NULL,
    note          VARCHAR(255),
    changed_by    BIGINT NULL,
    changed_at    DATETIME(6)    NOT NULL,
    CONSTRAINT fk_osh_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_osh_user FOREIGN KEY (changed_by) REFERENCES users (id)
);

CREATE TABLE shipments (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id          BIGINT      NOT NULL,
    courier_name      VARCHAR(60) NOT NULL,
    tracking_number   VARCHAR(60) NOT NULL,
    shipped_at        DATETIME(6)    NOT NULL,
    expected_delivery DATE,
    CONSTRAINT fk_shipments_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE TABLE payment_events (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id          BIGINT NULL,
    event_type        VARCHAR(40) NOT NULL,
    razorpay_event_id VARCHAR(60) NOT NULL UNIQUE,
    payload           JSON        NOT NULL,
    received_at       DATETIME(6)    NOT NULL,
    CONSTRAINT fk_pe_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE TABLE refunds (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id           BIGINT         NOT NULL,
    razorpay_refund_id VARCHAR(40) UNIQUE,
    amount             DECIMAL(10, 2) NOT NULL,
    status             VARCHAR(20)    NOT NULL,
    reason             VARCHAR(20)    NOT NULL,
    created_at         DATETIME(6)       NOT NULL,
    CONSTRAINT fk_refunds_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE TABLE returns (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_item_id BIGINT      NOT NULL UNIQUE,
    reason        VARCHAR(20) NOT NULL,
    comment       VARCHAR(1000),
    photo_urls    JSON,
    status        VARCHAR(20) NOT NULL,
    refund_amount DECIMAL(10, 2),
    refund_upi_id VARCHAR(60),
    upi_reference VARCHAR(60),
    admin_note    VARCHAR(500),
    restocked     BOOLEAN,
    requested_at  DATETIME(6)    NOT NULL,
    updated_at    DATETIME(6)    NOT NULL,
    CONSTRAINT fk_returns_item FOREIGN KEY (order_item_id) REFERENCES order_items (id)
);

-- ---------------------------------------------------------------------
-- E-BOOKS, REVIEWS, NOTIFICATIONS, AUDIT
-- ---------------------------------------------------------------------
CREATE TABLE user_library (
    user_id        BIGINT   NOT NULL,
    variant_id     BIGINT   NOT NULL,
    order_id       BIGINT   NOT NULL,
    download_count INT      NOT NULL DEFAULT 0,
    purchased_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, variant_id),
    CONSTRAINT fk_ul_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_ul_variant FOREIGN KEY (variant_id) REFERENCES book_variants (id),
    CONSTRAINT fk_ul_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE TABLE reading_progress (
    user_id     BIGINT   NOT NULL,
    variant_id  BIGINT   NOT NULL,
    last_page   INT      NOT NULL,
    total_pages INT      NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, variant_id),
    CONSTRAINT fk_rp2_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_rp2_variant FOREIGN KEY (variant_id) REFERENCES book_variants (id)
);

CREATE TABLE download_logs (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT   NOT NULL,
    variant_id    BIGINT   NOT NULL,
    ip_address    VARCHAR(45),
    downloaded_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_dl_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_dl_variant FOREIGN KEY (variant_id) REFERENCES book_variants (id)
);

CREATE TABLE reviews (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT        NOT NULL,
    book_id           BIGINT        NOT NULL,
    rating            INT           NOT NULL,
    title             VARCHAR(100),
    comment           VARCHAR(2000) NOT NULL,
    verified_purchase BOOLEAN       NOT NULL DEFAULT TRUE,
    helpful_count     INT           NOT NULL DEFAULT 0,
    report_count      INT           NOT NULL DEFAULT 0,
    status            VARCHAR(10)   NOT NULL DEFAULT 'VISIBLE',
    created_at        DATETIME(6)      NOT NULL,
    updated_at        DATETIME(6)      NOT NULL,
    CONSTRAINT fk_reviews_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_reviews_book FOREIGN KEY (book_id) REFERENCES books (id),
    CONSTRAINT uq_review UNIQUE (user_id, book_id),
    CONSTRAINT ck_review_rating CHECK (rating BETWEEN 1 AND 5),
    INDEX idx_reviews_book (book_id, status)
);

CREATE TABLE review_votes (
    review_id BIGINT NOT NULL,
    user_id   BIGINT NOT NULL,
    PRIMARY KEY (review_id, user_id),
    CONSTRAINT fk_rvote_review FOREIGN KEY (review_id) REFERENCES reviews (id),
    CONSTRAINT fk_rvote_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE review_reports (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    review_id  BIGINT       NOT NULL,
    user_id    BIGINT       NOT NULL,
    reason     VARCHAR(200) NOT NULL,
    created_at DATETIME(6)     NOT NULL,
    CONSTRAINT fk_rrep_review FOREIGN KEY (review_id) REFERENCES reviews (id),
    CONSTRAINT fk_rrep_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_report UNIQUE (review_id, user_id)
);

CREATE TABLE notifications (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    type       VARCHAR(40)  NOT NULL,
    title      VARCHAR(120) NOT NULL,
    message    VARCHAR(500) NOT NULL,
    link       VARCHAR(300),
    is_read    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at DATETIME(6)     NOT NULL,
    CONSTRAINT fk_notif_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_notif_user (user_id, is_read, created_at)
);

CREATE TABLE audit_logs (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    staff_id    BIGINT      NOT NULL,
    action      VARCHAR(60) NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id   VARCHAR(40) NOT NULL,
    old_value   JSON,
    new_value   JSON,
    ip_address  VARCHAR(45),
    created_at  DATETIME(6)    NOT NULL,
    CONSTRAINT fk_audit_staff FOREIGN KEY (staff_id) REFERENCES users (id),
    INDEX idx_audit (staff_id, created_at)
);
