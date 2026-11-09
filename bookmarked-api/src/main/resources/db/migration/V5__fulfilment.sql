-- Step 5: shipping items, cancellation bookkeeping and refund retries

-- Which shipment carried an item (an order can ship in several parcels)
ALTER TABLE order_items
    ADD COLUMN shipment_id BIGINT NULL,
    ADD CONSTRAINT fk_oi_shipment FOREIGN KEY (shipment_id) REFERENCES shipments (id);

-- Refund bookkeeping: why a refund failed, and when it last changed
ALTER TABLE refunds
    ADD COLUMN note       VARCHAR(255) NULL,
    ADD COLUMN updated_at DATETIME(6)  NULL;

CREATE INDEX idx_refunds_status ON refunds (status, created_at);
CREATE INDEX idx_osh_item ON order_status_history (order_item_id, changed_at);
