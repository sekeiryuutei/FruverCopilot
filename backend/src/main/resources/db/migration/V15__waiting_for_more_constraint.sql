ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_conversation_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_conversation_status_check
    CHECK (conversation_status IN ('WAITING_FOR_CUSTOMER','WAITING_FOR_MORE','READY_FOR_REVIEW','READY_FOR_APPROVAL','CONFIRMED','COMPLETED'));

