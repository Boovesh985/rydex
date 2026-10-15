# 14. Security hardening and personal data at rest

Date: 2026-10-15 · Status: Accepted

## Context
Before Phase 7 the user asked for a full audit, with three goals:
- no data breach: personal data protected even if the database leaks;
- passwords stored properly;
- no data loss.

What was already in place:
- RS256 tokens: 15-minute access tokens, plus refresh tokens that rotate, are stored as SHA-256 hashes and revoke the whole session family when reused (ADR 0006).
- A per-IP rate limit on sign-in (20 a minute).
- 404 rather than 403 for resources that are not the caller's.
- The outbox and idempotent consumers, so no event is lost (ADR 0009).
- Ride PINs that are derived from a secret and never stored.

The audit found these gaps:
- **Passwords:** bcrypt at cost 10, with a 72-byte limit that silently truncated long passwords; no rules beyond 8 characters.
- **Brute force:** guessing one account's password was limited only per IP, so many IPs could keep guessing.
- **Personal data:** emails and phone numbers were stored in plain text.
- **Development secrets:** a deployment could start with the published OTP secret or an ephemeral JWT key without anyone noticing.
- **Sign-out across tabs (web app):** two open tabs could both refresh with the same refresh token. The second use counts as reuse and signs the user out everywhere.
- **Housekeeping:** expired refresh tokens were never deleted.
- **Exposure:** Swagger UI was always public, and the web app sent no security headers.

## Decision

### Passwords
- New hashes are **Argon2id**, OWASP's first choice: 16 MiB of memory, 2 passes, a random 16-byte salt.
  - They are stored as `{argon2}…`, using Spring Security's `DelegatingPasswordEncoder` with Bouncy Castle.
- Existing bcrypt hashes still verify. They are re-hashed with Argon2id at the next successful sign-in, so no one has to reset a password.
- **Rules:** 8 to 128 characters, at least one letter and one digit, and not containing the email's local part. The register page ticks the rules off as the user types.
- **Sign-in page:** the password can be shown or hidden.

### Account lockout
- After 10 wrong passwords for one account within 15 minutes, sign-in answers 429 (with Retry-After) from every IP until the window ends.
- Unknown emails are counted the same way, so the lockout does not reveal which accounts exist.
- Both paths still cost one password check, so response times don't reveal it either.
- A successful sign-in resets the count.
- The counter lives in Redis and fails open, because the per-IP limit still applies.

### Personal data at rest
- **Email and phone are encrypted** with AES-256-GCM before they reach the database (`FieldCipher`, a JPA `AttributeConverter`).
  - Each value gets its own 96-bit IV and carries a `v1.` version prefix, which leaves room for key rotation.
  - Authenticated data binds each ciphertext to Rydex's PII columns.
  - A tampered value, or one encrypted under another key, fails to decrypt instead of returning garbage.
- **Lookup without plain text:** sign-in and uniqueness use `email_hash`, an HMAC-SHA256 of the normalised address under a second key (unique index). Two keys mean neither the index nor the ciphertext helps attack the other.
- **Migration V7** adds the columns.
  - `PiiProtector` runs right after Flyway and before the service takes requests. It encrypts rows that are still plain text: existing accounts, and the demo accounts the SQL seed writes, since SQL cannot reach the key. It then clears the plain columns.
  - A CHECK constraint keeps every row either fully encrypted or awaiting encryption.
- **What stays in plain text, and why:**
  - **Names:** they are shown to the other party of a ride and are in the token anyway.
  - **Ride coordinates and addresses:** matching, the admin heatmap and PostGIS queries need them. They are protected by the measures below instead.
- **Keys:** `PII_DATA_KEY` and `PII_INDEX_KEY`, each 32 random bytes in base64 (`openssl rand -base64 32`), kept in the deployment's secret environment.
  - **Losing the data key loses the emails and phone numbers**, so it is backed up with the database backups (Phase 10).

### Refusing development secrets
- `RYDEX_STRICT_SECRETS=true` (set on every deployment) makes ride-service refuse to start in any of these cases:
  - the published OTP secret, or one shorter than 32 characters;
  - the published PII keys, or the same key used twice;
  - no JWT key file, which would mean an ephemeral key.
- Locally the same problems only log a warning.
- This brings forward the fail-fast that ADR 0010 deferred to Phase 8.

### Web app
- **Token refresh is serialised across tabs** with the Web Locks API.
  - After waiting for the lock, a tab first reloads the session from storage. If another tab already traded the refresh token, it uses that tab's new tokens instead of replaying the old one.
  - Sign-in, sign-out and refreshes reach every open tab through the `storage` event.
- **Security headers** on Vercel (`vercel.json`):
  - a Content Security Policy that allows only Rydex's own origin, Google Fonts, OpenFreeMap tiles and Razorpay Checkout;
  - HSTS, `nosniff`, `X-Frame-Options: DENY`, a strict Referrer-Policy;
  - a Permissions-Policy that allows geolocation for Rydex only.
- **Clearer actions:**
  - every ride in a driver's history opens a trip page;
  - cancelling a ride as a driver asks for confirmation;
  - admins reach any ride from the operations view.

### Housekeeping and exposure
- Refresh tokens are deleted a day after they expire; revoked ones are kept until then, so reuse can still be detected. This runs hourly.
- `API_DOCS_ENABLED=false` hides Swagger UI and the OpenAPI document on all four services.

### Data loss
- **Already covered:** the outbox (ADR 0009); Redis with append-only persistence; Kafka with `acks=all` and idempotent producers.
- **Postgres:** gets nightly `pg_dump` backups in Phase 10 (master prompt §13).
- **In transit:** encrypted by Caddy's HTTPS and WSS (Phase 8/10).
- **At rest:** the database stays on a private network, behind a role per service with access to its own schema only (ADR 0004). OCI block volumes are encrypted at rest by default.

## Consequences
- **Database dumps:** a dump or a stolen volume no longer shows who the riders and drivers are.
- **Searching by email:** support can no longer search accounts by email in SQL; it has to go through the service.
- **Login cost:** Argon2id costs roughly 30–60 ms of CPU and 16 MiB of memory per sign-in. With 20 sign-ins a minute per IP that is negligible on the 4-core VM.
- **Key rotation:** it means re-encrypting the users table under a `v2.` prefix; it is not automated yet.
- **CSP and the backend domain:** the CSP allows any `*.duckdns.org` backend until the real domain is fixed in Phase 10, then it narrows to that host.
