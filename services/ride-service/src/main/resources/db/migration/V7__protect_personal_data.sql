-- Personal data at rest (ADR 0014).
-- Email and phone move to encrypted columns (AES-256-GCM, done by the service, which holds the key);
-- accounts are found by email through email_hash, a keyed HMAC of the normalised address.
-- The plain columns stay only as a hand-over point: the SQL demo seed writes there and the service
-- encrypts and clears them at startup (PiiProtector). The CHECK keeps every row in one of the two forms.
ALTER TABLE users
    ADD COLUMN email_hash varchar(64),
    ADD COLUMN email_enc  text,
    ADD COLUMN phone_enc  text;

ALTER TABLE users ALTER COLUMN email DROP NOT NULL;

CREATE UNIQUE INDEX ux_users_email_hash ON users (email_hash);

ALTER TABLE users ADD CONSTRAINT ck_users_email_protected
    CHECK (email IS NOT NULL OR (email_hash IS NOT NULL AND email_enc IS NOT NULL));

-- Argon2id hashes ({argon2}$argon2id$v=19$m=16384,t=2,p=1$salt$hash) are longer than bcrypt's.
ALTER TABLE users ALTER COLUMN password_hash TYPE varchar(255);

-- Expired refresh tokens are deleted by the hourly clean-up (RefreshTokenCleanup).
CREATE INDEX ix_refresh_tokens_expires ON refresh_tokens (expires_at);
