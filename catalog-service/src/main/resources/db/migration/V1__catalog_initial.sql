-- Catalog: the category tree, the admin-defined vocabulary sellers pick from, and the products
-- built out of both.
--
-- Money is BIGINT in Rial throughout. Not DECIMAL: Rial has no fractional unit in practice, and an
-- integer minor unit removes every rounding question at the cost of remembering the unit once.

CREATE EXTENSION IF NOT EXISTS ltree;

-- ---------------------------------------------------------------------------------------------
-- Categories
--
-- `path` is a materialised ltree of ancestor IDs, so "everything under Men's Clothing" is one
-- indexed subtree scan (path <@ '1.5') instead of a recursive CTE on every category page and every
-- search. Labels are IDs rather than slugs for two reasons: ltree labels only permit
-- [A-Za-z0-9_], which rules out hyphenated slugs outright, and IDs never change - so renaming a
-- category rewrites nothing. Only reparenting touches paths.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE categories
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_id   BIGINT       REFERENCES categories (id) ON DELETE RESTRICT,
    name        VARCHAR(100) NOT NULL,
    -- Globally unique: category URLs are flat (/c/mens-shirts), not nested.
    slug        VARCHAR(120) NOT NULL UNIQUE,
    -- Set by the application immediately after insert, since it needs the generated id.
    path        LTREE,
    -- Root categories are depth 0. Denormalised so the UI can render indentation without walking.
    depth       INT          NOT NULL DEFAULT 0,
    -- Maintained by trigger. A product may only attach where this is 0, and deriving that with a
    -- correlated subquery on every product write is the kind of cost that only shows up later.
    child_count INT          NOT NULL DEFAULT 0,
    sort_order  INT          NOT NULL DEFAULT 0,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_categories_parent ON categories (parent_id);
-- GIST is what makes <@ and @> index-backed; a btree on an ltree column does not help those.
CREATE INDEX idx_categories_path ON categories USING GIST (path);

/*
 * child_count has to be right for the leaf rule below to mean anything, and application code that
 * remembers to decrement it on every delete does not stay correct for long. A trigger is the only
 * place it cannot be forgotten.
 */
CREATE OR REPLACE FUNCTION categories_maintain_child_count() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.parent_id IS NOT NULL THEN
            UPDATE categories SET child_count = child_count + 1 WHERE id = NEW.parent_id;
        END IF;
    ELSIF TG_OP = 'DELETE' THEN
        IF OLD.parent_id IS NOT NULL THEN
            UPDATE categories SET child_count = child_count - 1 WHERE id = OLD.parent_id;
        END IF;
    ELSIF TG_OP = 'UPDATE' AND NEW.parent_id IS DISTINCT FROM OLD.parent_id THEN
        IF OLD.parent_id IS NOT NULL THEN
            UPDATE categories SET child_count = child_count - 1 WHERE id = OLD.parent_id;
        END IF;
        IF NEW.parent_id IS NOT NULL THEN
            UPDATE categories SET child_count = child_count + 1 WHERE id = NEW.parent_id;
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_categories_child_count
    AFTER INSERT OR UPDATE OR DELETE ON categories
    FOR EACH ROW EXECUTE FUNCTION categories_maintain_child_count();

-- ---------------------------------------------------------------------------------------------
-- Admin vocabulary: colors and sizes
--
-- Both exist so sellers pick from a list rather than typing. The previous schema had a free-text
-- size_name, which makes "XL", "xl" and "X-Large" three different values - fine until the search
-- sidebar tries to offer them as filters and shows all three.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE colors
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(50) NOT NULL UNIQUE,
    hex_code   CHAR(7)     NOT NULL,
    sort_order INT         NOT NULL DEFAULT 0,
    is_active  BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_colors_hex CHECK (hex_code ~ '^#[0-9A-Fa-f]{6}$')
);

CREATE TABLE sizes
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(50) NOT NULL,
    -- Optional scope: shoe sizes and shirt sizes should not share one list. NULL means global.
    category_id BIGINT      REFERENCES categories (id) ON DELETE SET NULL,
    sort_order  INT         NOT NULL DEFAULT 0,
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- NULLS NOT DISTINCT so two global sizes cannot share a name. Without it, NULL != NULL means
    -- the uniqueness check silently does nothing for exactly the global case.
    CONSTRAINT uq_sizes_name_scope UNIQUE NULLS NOT DISTINCT (name, category_id)
);

CREATE INDEX idx_sizes_category ON sizes (category_id);

-- ---------------------------------------------------------------------------------------------
-- Dynamic fields
--
-- Admin defines a field on a category; it applies to that category and everything beneath it.
-- Inheritance is resolved by walking the product category's ltree ancestors, which is why a field
-- defined on "Clothing" reaches "Clothing > Men > Shirts" without an admin redefining it there.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE fields
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_id   BIGINT       NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    name          VARCHAR(100) NOT NULL,
    slug          VARCHAR(120) NOT NULL,
    /*
     * SELECT and MULTI_SELECT are all requirement 5 asks for. The column exists so NUMBER and
     * BOOLEAN can be added later without a migration that changes what the column *means* - only
     * what values it accepts.
     */
    data_type     VARCHAR(20)  NOT NULL DEFAULT 'SELECT',
    is_required   BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Whether the search sidebar offers this as a facet.
    is_filterable BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order    INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_fields_slug_per_category UNIQUE (category_id, slug),
    CONSTRAINT ck_fields_data_type CHECK (data_type IN ('SELECT', 'MULTI_SELECT', 'NUMBER', 'BOOLEAN'))
);

CREATE INDEX idx_fields_category ON fields (category_id);

CREATE TABLE field_values
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    field_id   BIGINT       NOT NULL REFERENCES fields (id) ON DELETE CASCADE,
    value      VARCHAR(150) NOT NULL,
    slug       VARCHAR(170) NOT NULL,
    sort_order INT          NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_field_values_slug UNIQUE (field_id, slug)
);

CREATE INDEX idx_field_values_field ON field_values (field_id);

-- ---------------------------------------------------------------------------------------------
-- Products
-- ---------------------------------------------------------------------------------------------
CREATE TABLE products
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- The owning seller's user id in auth-service. No FK: separate service, separate database.
    seller_id    BIGINT       NOT NULL,
    -- Must be a leaf; enforced by trigger below as well as in the service.
    category_id  BIGINT       NOT NULL REFERENCES categories (id) ON DELETE RESTRICT,
    name         VARCHAR(255) NOT NULL,
    slug         VARCHAR(275) NOT NULL UNIQUE,
    description  TEXT,

    -- DRAFT/ACTIVE/ARCHIVED today. PENDING_REVIEW slots in later without changing what the column
    -- means, if approval is ever wanted - the plan says it is not, for now.
    status       VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',

    /*
     * Denormalised from variants and inventory. Sorting a category page by price, or filtering to
     * in-stock, would otherwise need an aggregate over every variant of every product on the page.
     * Recomputed whenever a variant or its stock changes.
     */
    min_price    BIGINT,
    max_price    BIGINT,
    total_stock  INT          NOT NULL DEFAULT 0,

    /*
     * Derived from product_field_values, never written directly. It exists so the search indexer
     * and product page get one row instead of a join per field. product_field_values remains the
     * source of truth precisely because JSONB cannot have a foreign key - the previous schema kept
     * only this, which meant nothing stopped a seller inventing a value that was not on the
     * admin's list.
     */
    attributes   JSONB        NOT NULL DEFAULT '{}'::jsonb,

    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,

    CONSTRAINT ck_products_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'))
);

CREATE INDEX idx_products_seller ON products (seller_id);
CREATE INDEX idx_products_category ON products (category_id);
-- Covers the storefront's default listing: active products in a category, newest first.
CREATE INDEX idx_products_active_listing ON products (category_id, created_at DESC)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_products_attributes ON products USING GIN (attributes);

/*
 * A product on a non-leaf category breaks the storefront's central assumption: that browsing a
 * parent means browsing everything beneath it. The service checks this too, but the read path
 * depends on it holding for every row however it got there.
 */
CREATE OR REPLACE FUNCTION products_require_leaf_category() RETURNS TRIGGER AS $$
DECLARE
    children INT;
BEGIN
    SELECT child_count INTO children FROM categories WHERE id = NEW.category_id;
    IF children > 0 THEN
        RAISE EXCEPTION 'Products may only be assigned to leaf categories (category % has % children)',
            NEW.category_id, children;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_products_leaf_category
    BEFORE INSERT OR UPDATE OF category_id ON products
    FOR EACH ROW EXECUTE FUNCTION products_require_leaf_category();

-- The seller's chosen values for the fields their category inherits. Real foreign keys, which is
-- the entire point: a value that is not on the admin's list cannot be stored at all.
CREATE TABLE product_field_values
(
    product_id     BIGINT NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    field_id       BIGINT NOT NULL REFERENCES fields (id) ON DELETE CASCADE,
    field_value_id BIGINT NOT NULL REFERENCES field_values (id) ON DELETE CASCADE,

    -- Includes field_value_id so MULTI_SELECT can hold several values for one field.
    PRIMARY KEY (product_id, field_id, field_value_id)
);

CREATE INDEX idx_pfv_field_value ON product_field_values (field_value_id);

-- Ordered media for a product. Referenced by id only; media-service owns the bytes and decides
-- whether a file is servable.
CREATE TABLE product_media
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id BIGINT      NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    media_id   UUID        NOT NULL,
    kind       VARCHAR(10) NOT NULL DEFAULT 'IMAGE',
    sort_order INT         NOT NULL DEFAULT 0,
    is_primary BOOLEAN     NOT NULL DEFAULT FALSE,

    CONSTRAINT uq_product_media UNIQUE (product_id, media_id),
    CONSTRAINT ck_product_media_kind CHECK (kind IN ('IMAGE', 'VIDEO'))
);

CREATE INDEX idx_product_media_product ON product_media (product_id);
-- One primary image per product, enforced rather than left to the application to remember.
CREATE UNIQUE INDEX uq_product_media_one_primary ON product_media (product_id) WHERE is_primary;

-- ---------------------------------------------------------------------------------------------
-- Variants
-- ---------------------------------------------------------------------------------------------
CREATE TABLE product_variants
(
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id        BIGINT       NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    -- Both nullable: a phone has neither colour nor size, a mug has colour but no size.
    color_id          BIGINT       REFERENCES colors (id) ON DELETE RESTRICT,
    size_id           BIGINT       REFERENCES sizes (id) ON DELETE RESTRICT,
    sku               VARCHAR(100) NOT NULL UNIQUE,
    -- Rial. Per variant because the same product sells at different prices per size.
    price             BIGINT       NOT NULL,
    -- Optional "was" price for showing a discount. Never used for charging.
    compare_at_price  BIGINT,
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    /*
     * NULLS NOT DISTINCT is load-bearing. Under the default NULL != NULL rule, a product with no
     * size could have unlimited rows all reading (product, red, NULL) - the constraint would never
     * fire for exactly the products that need it most.
     */
    CONSTRAINT uq_product_variant UNIQUE NULLS NOT DISTINCT (product_id, color_id, size_id),
    CONSTRAINT ck_variant_price_positive CHECK (price > 0),
    CONSTRAINT ck_variant_compare_price CHECK (compare_at_price IS NULL OR compare_at_price > price)
);

CREATE INDEX idx_product_variants_product ON product_variants (product_id);

-- ---------------------------------------------------------------------------------------------
-- Inventory
--
-- Separate from product_variants so stock movement - the hottest write in the system during a
-- sale - never contends with catalog reads of name, price and description.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE inventory
(
    variant_id        BIGINT PRIMARY KEY REFERENCES product_variants (id) ON DELETE CASCADE,
    quantity_on_hand  INT         NOT NULL DEFAULT 0,
    -- Held by unpaid orders. available = on_hand - reserved.
    quantity_reserved INT         NOT NULL DEFAULT 0,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_inventory_on_hand CHECK (quantity_on_hand >= 0),
    CONSTRAINT ck_inventory_reserved CHECK (quantity_reserved >= 0),
    -- Reserving more than exists would let two buyers both be promised the last unit.
    CONSTRAINT ck_inventory_not_oversold CHECK (quantity_reserved <= quantity_on_hand)
);

/*
 * Stock held for an order that has not been paid for yet.
 *
 * This is what lets checkout be correct without a distributed saga: order-service reserves, the
 * customer pays, the reservation commits. If they never pay, the TTL sweep releases it. Even if
 * order-service dies mid-checkout, the stock comes back on its own.
 */
CREATE TABLE stock_reservations
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    variant_id BIGINT      NOT NULL REFERENCES product_variants (id) ON DELETE CASCADE,
    -- order-service's id. No FK across the service boundary.
    order_id   BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'HELD',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    settled_at TIMESTAMPTZ,

    CONSTRAINT ck_reservation_quantity CHECK (quantity > 0),
    CONSTRAINT ck_reservation_status CHECK (status IN ('HELD', 'COMMITTED', 'RELEASED'))
);

CREATE INDEX idx_reservations_variant ON stock_reservations (variant_id);
CREATE INDEX idx_reservations_order ON stock_reservations (order_id);
-- Drives the sweep. Partial, because expired-and-already-settled rows are of no interest to it.
CREATE INDEX idx_reservations_expiring ON stock_reservations (expires_at) WHERE status = 'HELD';

-- ---------------------------------------------------------------------------------------------
-- Homepage banners (requirement 6)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE banners
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    media_id   UUID         NOT NULL,
    title      VARCHAR(150),
    link_url   VARCHAR(500),
    sort_order INT          NOT NULL DEFAULT 0,
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Optional scheduling window; NULL on either side means unbounded that way.
    starts_at  TIMESTAMPTZ,
    ends_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_banners_window CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);

CREATE INDEX idx_banners_active ON banners (sort_order) WHERE is_active;

-- ---------------------------------------------------------------------------------------------
-- Transactional outbox
--
-- Deferred here from auth-service, where the OTP path had no database write for an event to be
-- atomic with. Here it does: a product change and its ProductChanged event must commit together,
-- or the search index drifts from the catalog with nothing to detect the divergence.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE outbox
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id      UUID         NOT NULL UNIQUE,
    topic         VARCHAR(255) NOT NULL,
    -- Kafka partition key. Events for one product must stay ordered relative to each other, or a
    -- stale update can land after a newer one.
    partition_key VARCHAR(255),
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at  TIMESTAMPTZ,
    attempts      INT          NOT NULL DEFAULT 0,
    last_error    TEXT
);

CREATE INDEX idx_outbox_pending ON outbox (created_at) WHERE published_at IS NULL;
