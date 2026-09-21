-- An order may hold stock for many variants, but only one live hold per variant.
--
-- The service takes an advisory lock on the order id before checking whether a hold already
-- exists, which is what actually prevents the race. This is the backstop: two simultaneous retries
-- of one checkout each passing the "already held?" check and both taking the stock is a bug that
-- unit tests cannot see, because mocked repositories serialise. It was found by a concurrency test
-- against a real database, and the fix should not depend on that test continuing to exist.
--
-- Partial, on HELD only: an order can legitimately accumulate several settled rows for the same
-- variant over its life - held then released, then held again after the shopper retried payment.
-- Only one of them may be live at a time.
CREATE UNIQUE INDEX uq_reservation_one_live_hold_per_line
    ON stock_reservations (order_id, variant_id)
    WHERE status = 'HELD';
