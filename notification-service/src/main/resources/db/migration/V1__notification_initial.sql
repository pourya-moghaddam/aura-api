-- ---------------------------------------------------------------------------------------------
-- Delivery log
--
-- What was sent, to whom, and what the provider said. Three jobs, in order of importance:
--
-- 1. Idempotency. Kafka delivers at least once, so a consumer restarting mid-batch replays. An SMS
--    is not a database write - it costs money, it arrives on someone's phone, and a duplicated OTP
--    is actively confusing because the shopper cannot tell which code is live. The unique
--    constraint on event_id is what makes a replay a no-op rather than a second message.
--
-- 2. Evidence. When a customer says "I never got the code", the only useful answer comes from a
--    row that says whether the provider accepted it and what reference it gave back.
--
-- 3. A dead letter is invisible without this. A message that fails every retry disappears into a
--    DLT nobody reads; a FAILED row with the provider's own words is something an operator can
--    actually act on.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE sms_deliveries
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    /*
     * The event that asked for this message. Unique, and that is the whole idempotency mechanism:
     * a redelivery of the same event collides here instead of reaching the provider.
     *
     * Deliberately the event id and not the phone number - the same phone legitimately receives
     * many messages, and two OTP requests a minute apart are two different messages.
     */
    event_id            UUID         NOT NULL UNIQUE,

    kind                VARCHAR(30)  NOT NULL,

    /*
     * E.164. Stored because a delivery log that cannot say where the message went answers none of
     * the questions it exists to answer.
     */
    phone               VARCHAR(20)  NOT NULL,

    /*
     * The provider's template, when the message went through one. Null for free-text sends.
     *
     * Note what is absent: the OTP code itself. A one-time code written to a table outlives its
     * validity window and turns a database dump into a set of working credentials. The code exists
     * in transit and nowhere else.
     */
    template_id         INT,

    /*
     * The rendered text, for free-text messages only. Safe to keep - it is the same text the
     * recipient can read on their own phone - and without it a delivery log cannot tell an
     * operator what was actually said.
     */
    body                TEXT,

    status              VARCHAR(20)  NOT NULL,

    /* What the provider called it, so a support question can be traced into their dashboard. */
    provider_message_id VARCHAR(100),
    provider_cost       NUMERIC(10, 2),

    /*
     * How many times we tried. A row that reads SENT after four attempts is a different operational
     * story from one that succeeded first time, and the difference is invisible without a counter.
     */
    attempts            INT          NOT NULL DEFAULT 0,
    last_error          TEXT,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    sent_at             TIMESTAMPTZ,

    CONSTRAINT ck_sms_delivery_kind CHECK (kind IN ('OTP', 'ORDER_STATUS')),

    /*
     * PENDING - claimed, not yet answered for. A row stuck here means the service died mid-send.
     * SENT    - the provider accepted it.
     * FAILED  - the provider refused it, permanently. Retrying would fail identically.
     */
    CONSTRAINT ck_sms_delivery_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),

    CONSTRAINT ck_sms_delivery_sent_at CHECK (
        (status = 'SENT') = (sent_at IS NOT NULL))
);

-- "What has this number been sent lately", the shape every support question arrives in.
CREATE INDEX idx_sms_deliveries_phone ON sms_deliveries (phone, created_at DESC);

-- The operator's queue: what is broken, newest first. Partial, because the failures are a tiny
-- fraction of the table and nobody ever scans it for successes.
CREATE INDEX idx_sms_deliveries_failed ON sms_deliveries (created_at DESC)
    WHERE status = 'FAILED';
