CREATE TABLE users (
    id            uuid PRIMARY KEY,
    name          varchar(100) NOT NULL,
    email         varchar(254) NOT NULL,
    phone         varchar(20),
    password_hash varchar(100) NOT NULL,
    role          varchar(10)  NOT NULL CHECK (role IN ('RIDER', 'DRIVER', 'ADMIN')),
    created_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

-- Opaque refresh tokens, stored as SHA-256 hashes. Rotated on every use.
CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  varchar(64)    NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz
);

CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
