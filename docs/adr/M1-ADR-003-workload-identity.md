# M1-ADR-003: Spring authenticates to ramals-ai with Keycloak client credentials

- **Status:** Accepted
- **Date:** 2026-08-16
- **Relates to:** MVP-1 Canonical Package v1.3 Doc 05 §3, Doc 01 §4, M1-ADR-010
- **Required before:** M1-T03

## Context

`ramals-ai` must be able to tell that a caller is the RAMALS core workload. The threat is not an
anonymous request — those are trivially rejected — but a **learner token replayed** at the internal
API. A learner token is a legitimately issued, unexpired, correctly signed credential; signature
validation alone accepts it.

If that works, a learner can drive the agent layer directly, bypassing every authorization and
ownership check Spring performs before building the minimized context. The agent would receive
whatever context the caller supplied rather than what the platform decided the caller may see.

Keycloak is already deployed, already the issuer the platform trusts, and its JWKS path is already
proven end to end by the M0-T18 drill.

## Decision

Spring authenticates as **itself**, using the OAuth 2.0 client credentials grant against the existing
Keycloak realm.

- A dedicated confidential client — `ramals-core-workload` — with a service account. It is **not**
  `ramals-api` and **not** `ramals-web-ui`.
- Tokens are requested with audience **`ramals-ai`**.
- `ramals-ai` validates signature, issuer, expiry and — decisively — that `aud` contains `ramals-ai`
  and the token carries the expected service-account subject.

**Spring never forwards the learner's token.** The learner's identity travels as minimized context
inside the request body, after Spring has authorized the operation. Forwarding it would be privilege
laundering: the platform could no longer distinguish "the learner asked for this" from "a model
decided to do this".

**Audience separation is the mechanism that stops replay.** A learner token is issued for
`ramals-api`; it will never carry `ramals-ai`, so it fails validation at the internal boundary even
though it is otherwise perfectly valid. This must be tested with a *real* learner token, not a
mock — the M0-T18 drill exists because an inert protocol mapper passed every mock-JWT test.

Credentials for the service account come from the environment's secret management. The committed
realm gains the client definition; it never gains a secret.

## Alternatives considered

**Mutual TLS.** Strong, and independent of Keycloak availability on the hot path. Rejected for
MVP-1: it introduces certificate issuance, distribution, rotation and expiry monitoring to a stack
that currently has none of it, and the failure mode of an expired certificate is a total outage of
the AI path. Worth revisiting when the platform runs somewhere with certificate infrastructure.

**A short-lived JWT signed by Spring itself.** Avoids a Keycloak round trip, but creates a second
token issuer with its own signing key and rotation story, in a system whose entire identity story is
currently one issuer. The round trip is cacheable; a second key hierarchy is permanent.

**A shared secret header.** Rejected as a target design. If a local profile needs one temporarily it
must be environment-supplied, never committed, and carry a documented migration path — per Doc 05.

## Consequences

- `ramals-ai` needs the realm JWKS URL and its expected audience as configuration; both are
  non-secret.
- Token acquisition is cached and refreshed ahead of expiry, so it does not appear in the
  per-request deadline budget (M1-ADR-001).
- M1-T03's required tests include a real learner token being rejected at the internal boundary, and
  an expired or wrong-audience service token being rejected.
- Keycloak becoming unavailable degrades the AI path only. The deterministic core continues serving,
  as Doc 06 §4 requires.
