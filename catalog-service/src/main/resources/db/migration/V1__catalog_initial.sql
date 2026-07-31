CREATE TABLE categories
(
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100)        NOT NULL,
    slug       VARCHAR(120) UNIQUE NOT NULL,
    parent_id  BIGINT              REFERENCES categories (id) ON DELETE SET NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE category_fields
(
    id          BIGSERIAL PRIMARY KEY,
    category_id BIGINT       NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    is_required BOOLEAN DEFAULT TRUE
);

CREATE TABLE field_choices
(
    id           BIGSERIAL PRIMARY KEY,
    field_id     BIGINT       NOT NULL REFERENCES category_fields (id) ON DELETE CASCADE,
    choice_value VARCHAR(100) NOT NULL
);

CREATE TABLE colors
(
    id       BIGSERIAL PRIMARY KEY,
    name     VARCHAR(50) NOT NULL,
    hex_code VARCHAR(10) NOT NULL
);

CREATE TABLE products
(
    id          BIGSERIAL PRIMARY KEY,
    category_id BIGINT              NOT NULL REFERENCES categories (id),
    seller_id   BIGINT              NOT NULL,
    name        VARCHAR(255)        NOT NULL,
    slug        VARCHAR(275) UNIQUE NOT NULL,
    description TEXT,
    attributes  JSONB                        DEFAULT '{}'::jsonb,
    is_active   BOOLEAN             NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE product_variants
(
    id         BIGSERIAL PRIMARY KEY,
    product_id BIGINT              NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    color_id   BIGINT REFERENCES colors (id),
    size_name  VARCHAR(50)         NOT NULL,
    price      DECIMAL(12, 2)      NOT NULL,
    sku        VARCHAR(100) UNIQUE NOT NULL
);

CREATE INDEX idx_categories_parent ON categories (parent_id);
CREATE INDEX idx_products_category ON products (category_id);
CREATE INDEX idx_product_variants_product ON product_variants (product_id);