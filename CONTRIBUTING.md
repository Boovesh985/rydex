# Contributing to Rydex

Thanks for your interest. Bug reports, ideas and pull requests are all welcome.

## Before you start

- For anything larger than a small fix, open an issue first so we can agree on the approach.
- Read the [README](README.md) and the [guide](docs/GUIDE.md) to get the stack running locally.
- Design decisions are recorded in [docs/adr](docs/adr). A change that alters one (a new technology, a new event, a different data flow) comes with a new ADR.

## Development setup

You need Docker, JDK 21, Node.js 22.12+ and Python 3.12+.

```bash
docker compose -f infra/docker-compose.yml up -d   # Postgres + PostGIS, Redis, Kafka
./mvnw verify                                       # backend build and tests
cd frontend && npm install && npm run dev           # web app on http://localhost:5173
cd simulator && pip install -r requirements.txt     # simulator
```

The four services run with `../../mvnw spring-boot:run` from each `services/*` directory. On a machine with 8 GB of RAM, stop the services before a full `./mvnw verify`.

## Making a change

1. Fork the repository and create a branch from `main`, for example `fix/offer-timeout` or `feat/scheduled-rides`.
2. Keep the change focused: one topic per pull request.
3. Add or update tests with the change:
   - backend: JUnit 5, integration tests with Testcontainers;
   - web app: Vitest + Testing Library, Playwright for end-to-end flows;
   - simulator: pytest.
4. Run the checks below, then open a pull request and fill in the template.

### Checks

```bash
./mvnw verify
cd frontend && npm run lint && npm run typecheck && npm run format:check && npm test
cd simulator && python -m pytest
```

With the infrastructure and the four services running, `infra/scripts/smoke-test.ps1` and `npm run e2e` (in `frontend/`) test a full ride end to end. CI runs everything on every push.

## Conventions

- **Commits** follow [Conventional Commits](https://www.conventionalcommits.org): `feat(web): …`, `fix(ride): …`, `docs: …`, `test(e2e): …`, `ci: …`. Write the subject in the imperative and explain the why in the body.
- **Java:** match the surrounding code; constructor injection; no field injection.
- **Frontend:** Prettier formats the code (`npm run format`, 160 columns, single quotes); ESLint must pass.
- **Database:** schema changes go in a new Flyway migration (`V<n>__description.sql`). Never edit a migration that has been applied.
- **Events:** a change to an event payload updates its JSON Schema in [contracts/events](contracts/events), stays backwards compatible, and updates every consumer.
- **Dependencies:** pin exact versions, at least two weeks old.
- **Secrets:** never commit keys or `.env` files. Development keys are published on purpose; real ones come from the environment.

## Reporting bugs and ideas

Use the [issue templates](https://github.com/Boovesh985/rydex/issues/new/choose). For a security problem, do not open an issue: follow [SECURITY.md](SECURITY.md).

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE) and that you follow the [Code of Conduct](CODE_OF_CONDUCT.md).
