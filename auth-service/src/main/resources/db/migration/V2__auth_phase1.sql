-- Phase 1 schema: refresh tokens, address book, transactional outbox.
--
-- A new migration rather than an edit to V1: V1 has now been applied to real databases, and
-- changing it would fail Flyway's checksum validation on every existing environment.

-- ---------------------------------------------------------------------------------------------
-- Users: names, needed for the address book and for order snapshots later.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN first_name VARCHAR(100),
    ADD COLUMN last_name  VARCHAR(100);

-- ---------------------------------------------------------------------------------------------
-- Refresh tokens
--
-- Stored hashed, never in plaintext: a database leak would otherwise hand over live 30-day
-- sessions for every user. SHA-256 without a salt is deliberate here — unlike a password, the
-- token is 256 bits of entropy we generated, so there is nothing to brute-force and we need
-- constant-time lookup by hash.
--
-- `family_id` groups a token and all its rotated successors. Presenting a token that was already
-- consumed means someone replayed a stolen one, so the whole family is revoked rather than just
-- that token — otherwise the thief and the victim simply take turns refreshing.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE refresh_tokens
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  CHAR(64)    NOT NULL UNIQUE,
    family_id   UUID        NOT NULL,
    audience    VARCHAR(20) NOT NULL,
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ NOT NULL,
    -- Set when this token is exchanged for a new one. A second presentation after this is set
    -- is the reuse signal.
    consumed_at TIMESTAMPTZ,
    -- Set when the family is killed: logout, password change, or detected reuse.
    revoked_at  TIMESTAMPTZ
);

CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
-- Supports the cleanup job that deletes long-expired rows.
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);

-- ---------------------------------------------------------------------------------------------
-- Address book
--
-- Profile data, owned by auth-service. Orders snapshot these values rather than referencing them,
-- so editing an address never rewrites what a past order says was delivered where.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE addresses
(
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id              BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- User-facing label: "Home", "Office".
    title                VARCHAR(100),
    recipient_first_name VARCHAR(100) NOT NULL,
    recipient_last_name  VARCHAR(100) NOT NULL,
    -- E.164, same normalisation as users.phone. The recipient is not always the account holder.
    phone                VARCHAR(20)  NOT NULL,
    province             VARCHAR(100) NOT NULL,
    city                 VARCHAR(100) NOT NULL,
    line1                TEXT         NOT NULL,
    line2                TEXT,
    -- Iranian postal codes are exactly 10 digits.
    postal_code          VARCHAR(10)  NOT NULL,
    is_default           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_addresses_postal_code CHECK (postal_code ~ '^[0-9]{10}$')
);

CREATE INDEX idx_addresses_user ON addresses (user_id);

-- At most one default per user, enforced by the database rather than by application code that
-- has to remember to clear the previous default inside the same transaction.
CREATE UNIQUE INDEX uq_addresses_one_default_per_user
    ON addresses (user_id) WHERE is_default;

-- ---------------------------------------------------------------------------------------------
-- Transactional outbox
--
-- Replaces publishing to Kafka directly from inside a transaction, which is a dual write: the
-- commit and the broker send can succeed independently, so the database and the event stream
-- drift apart with nothing to detect it. Writing the event here in the same transaction as the
-- state change makes the pair atomic; a poller publishes afterwards.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE outbox
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Matches DomainEvent.eventId, so consumers can deduplicate across redeliveries.
    event_id     UUID         NOT NULL UNIQUE,
    topic        VARCHAR(255) NOT NULL,
    -- Kafka partition key: events for one aggregate must stay ordered relative to each other.
    partition_key VARCHAR(255),
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    attempts     INT          NOT NULL DEFAULT 0,
    last_error   TEXT
);

-- Partial index: the poller only ever scans unpublished rows, and this keeps that scan cheap
-- even once the table holds millions of already-published events.
CREATE INDEX idx_outbox_pending ON outbox (created_at) WHERE published_at IS NULL;
