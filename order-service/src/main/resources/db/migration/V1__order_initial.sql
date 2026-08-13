-- Orders: the cart, the checkout, and everything the money touches.
--
-- Money is BIGINT in Rial throughout, matching catalog. Not DECIMAL: Rial has no fractional unit
-- in practice, and an integer minor unit removes every rounding question at the cost of
-- remembering the unit once.
--
-- No foreign keys leave this database. Products, variants and users live in other services with
-- their own schemas, so those columns are plain ids and the services are the guarantee.

-- ---------------------------------------------------------------------------------------------
-- Delivery methods
--
-- Owned here rather than in catalog, even though every other admin-managed lookup table lives
-- there. Ownership follows the transactional reader: checkout prices delivery inside its own
-- transaction, and a cross-service call to catalog on every checkout would be slower and a new
-- way for checkout to fail. The control panel being one screen does not mean it talks to one
-- service.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE delivery_methods
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    -- Flat, by decision. No destination or weight based pricing in the first release, so checkout
    -- reads a fee rather than requesting a quote.
    fee         BIGINT       NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_delivery_method_name UNIQUE (name),
    CONSTRAINT ck_delivery_fee_non_negative CHECK (fee >= 0)
);

CREATE INDEX idx_delivery_methods_active ON delivery_methods (sort_order) WHERE is_active;

-- ---------------------------------------------------------------------------------------------
-- Carts
--
-- In PostgreSQL, not Redis. Carts are business data: they survive for weeks, they feed
-- abandoned-cart reporting, and losing one to an eviction or a restart is lost revenue. Redis
-- stays available as a read-through cache if profiling ever justifies it.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE carts
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- Exactly one of these two identifies the cart. A signed-in shopper's cart follows the
    -- account; a guest's follows an opaque token in an httpOnly cookie, which is what makes
    -- requirement 12 work without an account.
    user_id    BIGINT,
    cart_token UUID,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_cart_owner CHECK (
        (user_id IS NOT NULL AND cart_token IS NULL)
            OR (user_id IS NULL AND cart_token IS NOT NULL)
    )
);

-- One live cart per owner. Partial rather than plain UNIQUE because the other column is NULL for
-- each kind of owner, and NULLs would otherwise never collide.
CREATE UNIQUE INDEX uq_cart_user ON carts (user_id) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX uq_cart_token ON carts (cart_token) WHERE cart_token IS NOT NULL;
-- Drives the scheduled sweep of abandoned guest carts.
CREATE INDEX idx_carts_stale ON carts (updated_at) WHERE user_id IS NULL;

CREATE TABLE cart_items
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cart_id      BIGINT      NOT NULL REFERENCES carts (id) ON DELETE CASCADE,
    -- catalog-service's ids. Kept alongside so a cart line can be shown without a round trip.
    product_id   BIGINT      NOT NULL,
    variant_id   BIGINT      NOT NULL,
    quantity     INT         NOT NULL,

    /*
     * What the variant cost when it went in the cart. Never charged: prices are re-read at
     * checkout, and this exists only so the cart can say "the price of this item changed since
     * you added it". Treating it as the price to charge is how a shopper pays last month's price.
     */
    price_at_add BIGINT      NOT NULL,

    added_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Adding the same variant twice raises the quantity rather than making a second line.
    CONSTRAINT uq_cart_item_variant UNIQUE (cart_id, variant_id),
    CONSTRAINT ck_cart_item_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_cart_items_cart ON cart_items (cart_id);

-- ---------------------------------------------------------------------------------------------
-- Discounts
-- ---------------------------------------------------------------------------------------------
CREATE TABLE discount_codes
(
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            VARCHAR(50)  NOT NULL,
    description     TEXT,
    -- PERCENTAGE applies `value` percent; FIXED takes `value` Rial off.
    type            VARCHAR(20)  NOT NULL,
    value           BIGINT       NOT NULL,
    -- Ceiling for a percentage discount, so "50% off" cannot take an unbounded amount.
    max_discount    BIGINT,
    min_order_total BIGINT       NOT NULL DEFAULT 0,
    -- NULL means unlimited on either axis.
    usage_limit     INT,
    per_user_limit  INT,
    times_used      INT          NOT NULL DEFAULT 0,
    starts_at       TIMESTAMPTZ,
    ends_at         TIMESTAMPTZ,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- Case-insensitive: shoppers type codes by hand, in whatever case they like.
    CONSTRAINT uq_discount_code UNIQUE (code),
    CONSTRAINT ck_discount_type CHECK (type IN ('PERCENTAGE', 'FIXED')),
    CONSTRAINT ck_discount_value_positive CHECK (value > 0),
    CONSTRAINT ck_discount_percentage_range CHECK (type <> 'PERCENTAGE' OR value <= 100),
    CONSTRAINT ck_discount_window CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_discount_usage_non_negative CHECK (times_used >= 0)
);

CREATE UNIQUE INDEX uq_discount_code_lower ON discount_codes (LOWER(code));

-- ---------------------------------------------------------------------------------------------
-- Orders
-- ---------------------------------------------------------------------------------------------
CREATE TABLE orders
(
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    /*
     * What the customer quotes. Ten characters of Crockford Base32 from SecureRandom, unrelated to
     * the primary key - a sequential id in a URL tells anyone who buys twice exactly how many
     * orders the shop has taken in between.
     */
    trace_code         VARCHAR(16)  NOT NULL,

    -- NULL for a guest checkout, which is requirement 12's whole point.
    user_id            BIGINT,

    /*
     * Snapshots, not references. The address book lives in auth-service and its rows are editable;
     * an order has to keep saying where it was actually sent, so editing an address must never
     * rewrite order history.
     */
    buyer_phone        VARCHAR(20)  NOT NULL,
    buyer_first_name   VARCHAR(100) NOT NULL,
    buyer_last_name    VARCHAR(100) NOT NULL,
    address_snapshot   JSONB        NOT NULL,
    postal_code        VARCHAR(10)  NOT NULL,

    -- Same reasoning: an admin changing a delivery price must not change what a past order paid.
    delivery_method_id BIGINT       REFERENCES delivery_methods (id) ON DELETE RESTRICT,
    delivery_name      VARCHAR(100) NOT NULL,
    delivery_fee       BIGINT       NOT NULL,

    discount_code_id   BIGINT       REFERENCES discount_codes (id) ON DELETE RESTRICT,
    discount_code      VARCHAR(50),
    discount_amount    BIGINT       NOT NULL DEFAULT 0,

    subtotal           BIGINT       NOT NULL,
    total              BIGINT       NOT NULL,

    payment_status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING',

    /*
     * Derived from the items, never set directly. One cart can hold several sellers' products and
     * each seller advances only their own items (requirement 8), so the order as a whole is only
     * as far along as its least-advanced item.
     */
    derived_status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING',

    -- CUSTOMER for a normal checkout, SELLER_LINK for an order a seller composed (requirement 1).
    source             VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER',

    /*
     * Set by the client on checkout. A retried request with the same key returns the original
     * order instead of taking payment twice - the shopper's browser retrying a timed-out POST is
     * ordinary, and without this it buys twice.
     */
    idempotency_key    VARCHAR(100),

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    paid_at            TIMESTAMPTZ,

    CONSTRAINT uq_order_trace_code UNIQUE (trace_code),
    CONSTRAINT ck_order_payment_status CHECK (
        payment_status IN ('PENDING', 'PAID', 'FAILED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_order_derived_status CHECK (
        derived_status IN ('PENDING', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    CONSTRAINT ck_order_source CHECK (source IN ('CUSTOMER', 'SELLER_LINK')),
    CONSTRAINT ck_order_amounts_non_negative CHECK (
        subtotal >= 0 AND total >= 0 AND delivery_fee >= 0 AND discount_amount >= 0)
);

CREATE INDEX idx_orders_user ON orders (user_id, created_at DESC) WHERE user_id IS NOT NULL;
CREATE INDEX idx_orders_phone ON orders (buyer_phone, created_at DESC);
CREATE INDEX idx_orders_payment_status ON orders (payment_status, created_at);
-- One idempotency key means one order. Partial, because most orders carry none.
CREATE UNIQUE INDEX uq_orders_idempotency_key ON orders (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE order_items
(
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id              BIGINT       NOT NULL REFERENCES orders (id) ON DELETE CASCADE,

    product_id            BIGINT       NOT NULL,
    variant_id            BIGINT       NOT NULL,

    /*
     * Which seller owes this line. Carries no financial meaning - all money lands in one merchant
     * account and settlement happens out of band - it exists so a seller can see and advance their
     * own items without seeing anyone else's.
     */
    seller_id             BIGINT       NOT NULL,

    -- Snapshots again: the product may later be renamed, repriced, or archived entirely, and the
    -- order still has to render.
    product_name_snapshot VARCHAR(255) NOT NULL,
    variant_snapshot      JSONB        NOT NULL,

    unit_price            BIGINT       NOT NULL,
    quantity              INT          NOT NULL,
    line_total            BIGINT       NOT NULL,

    /*
     * The status a seller actually moves. Modelling this only on the order, as the original
     * architecture did, makes requirement 8 unimplementable the moment two sellers appear in one
     * cart.
     */
    fulfillment_status    VARCHAR(20)  NOT NULL DEFAULT 'PENDING',

    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_order_item_fulfillment_status CHECK (
        fulfillment_status IN ('PENDING', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    CONSTRAINT ck_order_item_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_item_amounts CHECK (unit_price >= 0 AND line_total >= 0)
);

CREATE INDEX idx_order_items_order ON order_items (order_id);
-- The seller's own queue: their items, newest first.
CREATE INDEX idx_order_items_seller ON order_items (seller_id, created_at DESC);

-- ---------------------------------------------------------------------------------------------
-- Discount redemptions
--
-- A row per use, so per-user limits are answerable and a limited code cannot be over-redeemed by
-- concurrent checkouts - the code row is locked while this is written.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE discount_redemptions
(
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    discount_code_id BIGINT      NOT NULL REFERENCES discount_codes (id) ON DELETE CASCADE,
    order_id         BIGINT      NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    -- NULL for a guest; per-user limits simply do not apply to them.
    user_id          BIGINT,
    amount           BIGINT      NOT NULL,
    redeemed_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- One redemption per order, so a retried checkout cannot consume the code twice.
    CONSTRAINT uq_redemption_order UNIQUE (order_id)
);

CREATE INDEX idx_redemptions_code_user ON discount_redemptions (discount_code_id, user_id);

-- ---------------------------------------------------------------------------------------------
-- Payments
-- ---------------------------------------------------------------------------------------------
CREATE TABLE payments
(
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id        BIGINT       NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    gateway         VARCHAR(30)  NOT NULL DEFAULT 'ZARINPAL',

    -- Zarinpal's handle for the attempt, returned by payment/request and quoted back at verify.
    authority       VARCHAR(100),
    amount          BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',

    -- Proof of payment, from the verify response. Not from the callback: Status=OK there is a
    -- query parameter anyone can type.
    ref_id          VARCHAR(50),
    card_pan_masked VARCHAR(30),

    idempotency_key VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    verified_at     TIMESTAMPTZ,

    CONSTRAINT ck_payment_status CHECK (
        status IN ('PENDING', 'PAID', 'FAILED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_payment_amount_positive CHECK (amount > 0)
);

CREATE UNIQUE INDEX uq_payments_authority ON payments (authority) WHERE authority IS NOT NULL;
CREATE INDEX idx_payments_order ON payments (order_id);
-- Feeds the reconciliation sweep: payments left pending past a threshold are asked about directly,
-- because callbacks do get lost and the alternative is a customer who paid and got nothing.
CREATE INDEX idx_payments_pending ON payments (created_at) WHERE status = 'PENDING';

-- Every raw payload from the gateway, kept verbatim. The only thing that settles a dispute about
-- what was actually said and when.
CREATE TABLE payment_events
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id  BIGINT      NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
    type        VARCHAR(40) NOT NULL,
    raw_payload JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_events_payment ON payment_events (payment_id, created_at);

-- ---------------------------------------------------------------------------------------------
-- Seller order links (requirement 1, seller scope)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE seller_order_links
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id   BIGINT      NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    seller_id  BIGINT      NOT NULL,
    -- Hashed, not stored raw: the token is a bearer credential for an unauthenticated stranger,
    -- and a leaked database should not hand out working links.
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_seller_order_link_token UNIQUE (token_hash),
    CONSTRAINT uq_seller_order_link_order UNIQUE (order_id)
);

CREATE INDEX idx_seller_order_links_expiring ON seller_order_links (expires_at) WHERE used_at IS NULL;

-- ---------------------------------------------------------------------------------------------
-- Outbox
--
-- Same shape and the same reasoning as catalog's: an event and the state change it describes have
-- to commit together, or a broker hiccup leaves an order advanced and the buyer never told.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE outbox
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id      UUID         NOT NULL UNIQUE,
    topic         VARCHAR(255) NOT NULL,
    partition_key VARCHAR(255),
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at  TIMESTAMPTZ,
    attempts      INT          NOT NULL DEFAULT 0,
    last_error    TEXT
);

CREATE INDEX idx_outbox_pending ON outbox (created_at) WHERE published_at IS NULL;
