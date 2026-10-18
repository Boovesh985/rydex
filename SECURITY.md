# Security policy

## Supported versions

Rydex is in active development. Security fixes go to the `main` branch only.

## Reporting a vulnerability

Please do not open a public issue for a security problem.

Report it privately through GitHub: on the repository's **Security** tab, choose **Report a vulnerability** ([direct link](https://github.com/Boovesh985/rydex/security/advisories/new)). Include:

- what the problem is and where (service, endpoint or file);
- how to reproduce it, as a minimal set of steps or requests;
- the impact you expect (for example, reading another rider's trips).

You will get a reply within 7 days. Once the problem is confirmed, a fix is prepared and released, and you are credited in the advisory unless you prefer otherwise.

## Scope

In scope: the services, the web app, the simulator and the infrastructure configuration in this repository.

Out of scope:

- the development keys and passwords committed on purpose for local use (the JWT dev key fallback, `Rydex@123` demo accounts, the development OTP and personal-data keys); a deployment must replace them, and `RYDEX_STRICT_SECRETS=true` refuses to start with them;
- denial of service through volume alone;
- vulnerabilities in third-party services (Razorpay, OpenFreeMap, OSRM, Nominatim): report those to their owners.

## Security design

How personal data, passwords, tokens and secrets are handled is described in [ADR 0006](docs/adr/0006-auth-rs256-jwks-rotating-refresh.md) and [ADR 0014](docs/adr/0014-security-hardening-and-personal-data.md).
