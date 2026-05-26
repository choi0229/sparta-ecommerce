ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS shipment_status VARCHAR(20) NULL;

COMMENT ON COLUMN orders.shipment_status IS 'logistics-api shipment-event 수신 후 반영되는 배송 상태 (READY, SHIPPED, IN_TRANSIT, DELIVERED, FAILED, CANCELED)';
