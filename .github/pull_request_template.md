## What and why

<!-- What does this change, and why? Link the issue: "Closes #123". -->

## How it was tested

<!-- Tests added or updated, and anything checked by hand. -->

## Checklist

- [ ] Tests cover the change, and `./mvnw verify` / `npm test` / `pytest` pass locally
- [ ] Frontend: `npm run lint`, `npm run typecheck` and `npm run format:check` pass
- [ ] Database changes are in a new Flyway migration (no applied migration edited)
- [ ] Event payload changes update `contracts/events` and every consumer
- [ ] A changed design decision has an ADR in `docs/adr`
- [ ] The README or the guide is updated if setup or behaviour changed
