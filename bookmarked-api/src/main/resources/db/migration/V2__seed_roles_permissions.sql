-- Fixed roles and permissions (see spec section 10).

INSERT INTO roles (name) VALUES
    ('CUSTOMER'),
    ('SUPER_ADMIN'),
    ('INVENTORY_MANAGER'),
    ('ORDER_MANAGER'),
    ('DEMO_ADMIN');

INSERT INTO permissions (name) VALUES
    ('BOOK_READ'),
    ('BOOK_WRITE'),
    ('CATEGORY_WRITE'),
    ('INVENTORY_UPDATE'),
    ('ORDER_READ'),
    ('ORDER_UPDATE'),
    ('RETURN_MANAGE'),
    ('REFUND_ISSUE'),
    ('REVIEW_MODERATE'),
    ('BANNER_MANAGE'),
    ('COUPON_MANAGE'),
    ('USER_MANAGE'),
    ('STAFF_MANAGE'),
    ('ANALYTICS_VIEW'),
    ('AUDIT_VIEW');

-- SUPER_ADMIN: everything
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'SUPER_ADMIN';

-- INVENTORY_MANAGER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.name IN ('BOOK_READ', 'BOOK_WRITE', 'CATEGORY_WRITE', 'INVENTORY_UPDATE',
                'ORDER_READ', 'BANNER_MANAGE', 'ANALYTICS_VIEW')
WHERE r.name = 'INVENTORY_MANAGER';

-- ORDER_MANAGER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.name IN ('BOOK_READ', 'ORDER_READ', 'ORDER_UPDATE', 'RETURN_MANAGE',
                'REFUND_ISSUE', 'REVIEW_MODERATE', 'ANALYTICS_VIEW')
WHERE r.name = 'ORDER_MANAGER';

-- DEMO_ADMIN: read-only tour of the admin panel for recruiters
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.name IN ('BOOK_READ', 'ORDER_READ', 'ANALYTICS_VIEW')
WHERE r.name = 'DEMO_ADMIN';
