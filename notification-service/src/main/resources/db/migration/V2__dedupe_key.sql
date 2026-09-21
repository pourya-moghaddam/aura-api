-- ---------------------------------------------------------------------------------------------
-- Separate "what caused this message" from "what decides whether to send it"
--
-- Until now those were the same thing: one event, one message, deduplicated on the event id. Order
-- status breaks that. A delivered order raises one OrderItemStatusChanged per item, and a shopper
-- who hears "your order has been delivered" three times for one parcel reads it as a mistake -
-- so the *order* is what must be sent about once, not the event.
--
-- Deriving a key from the order id and status gives that, and reuses the unique constraint that
-- already works rather than adding a second mechanism. But writing a synthetic value into a column
-- called event_id would make the table lie about which event it came from, and that column is the
-- only thread back to the log line and the Kafka offset when something needs explaining.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE sms_deliveries
    ADD COLUMN dedupe_key VARCHAR(120);

-- Existing rows deduplicated on the event id, so that is exactly what their key was.
UPDATE sms_deliveries SET dedupe_key = event_id::text WHERE dedupe_key IS NULL;

ALTER TABLE sms_deliveries
    ALTER COLUMN dedupe_key SET NOT NULL,
    ADD CONSTRAINT uq_sms_deliveries_dedupe_key UNIQUE (dedupe_key);

-- event_id keeps its NOT NULL and its index, but gives up uniqueness: one delivered order is one
-- message caused by whichever item's event arrived first, and the losing events never write a row
-- at all. Indexed rather than unique, because "which event produced this" is still the first
-- question anyone asks of a row.
ALTER TABLE sms_deliveries
    DROP CONSTRAINT sms_deliveries_event_id_key;

CREATE INDEX idx_sms_deliveries_event ON sms_deliveries (event_id);
