# 6. RS256 access tokens via JWKS, rotating opaque refresh tokens

Date: 2026-10-10 · Status: Accepted

## Context
Five services need to authenticate the same users. Only ride-service should be able to mint tokens. The frontend is a SPA on another origin, so cookies would need cross-site configuration.

## Decision
- **Access token**: JWT signed with **RS256**, 15 min TTL. Claims: `iss=rydex`, `sub=<userId>`, `role`, `name`. It is issued with Spring Security's `NimbusJwtEncoder`, so there is no extra JWT library.
- **Key distribution**: ride-service publishes its public key at `GET /.well-known/jwks.json` (the `kid` is the RFC 7638 thumbprint). Other services use `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`, so no secret is shared. The private key is loaded from a PEM file (`infra/scripts/gen-jwt-keys.sh`). If no key file exists, ride-service generates an ephemeral key and logs a warning, which only suits development.
- **Refresh token**: 32 random bytes, returned once and stored only as a SHA-256 hash, 14-day TTL. **Rotated on every use.** Presenting an already-rotated token is treated as theft, and every active refresh token of that user is revoked.
- **Login**: an unknown email still costs one bcrypt comparison, so response timing does not reveal which emails are registered.
- **Authorization**: the `role` claim maps to `ROLE_*` authorities (`@PreAuthorize`). Ownership checks (rider owns the ride, driver is the assigned driver) live in the service layer. When the caller is not allowed to see a ride, the response is **404**, not 403.

## Consequences
- Revoking access tokens before they expire is not possible, so the 15-minute TTL bounds the exposure.
- Rotating the signing key signs everyone out unless both keys are published during a transition period. That is acceptable at this scale.
