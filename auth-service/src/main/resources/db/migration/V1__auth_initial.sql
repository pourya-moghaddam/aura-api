CREATE TABLE users
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email      VARCHAR(255) UNIQUE,
    -- Stored in E.164 form. Normalisation happens before any lookup, so that Persian-digit and
    -- 0-prefixed variants of the same number cannot become two accounts.
    phone      VARCHAR(20) NOT NULL UNIQUE,
    -- Null until the user sets one; sign-up is OTP-only.
    password   VARCHAR(255),
    is_active  BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE roles
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(50) NOT NULL UNIQUE,
    description TEXT
);

CREATE TABLE user_roles
(
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_roles_role ON user_roles (role_id);

-- Without these rows, creating the first user fails: the sign-up path looks up the USER role by
-- name and has nothing to attach.
INSERT INTO roles (name, description)
VALUES ('SUPER_ADMIN', 'Full control. Manages users and role assignments.'),
       ('ADMIN', 'Manages catalog metadata: categories, fields, colors, sizes, banners.'),
       ('SELLER', 'Defines products and fulfils orders for their own items.'),
       ('USER', 'Storefront customer. Default role for every new account.');
