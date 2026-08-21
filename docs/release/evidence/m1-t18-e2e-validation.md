# M1-T18 — MVP-1 end-to-end validation of the release candidate

**Date (UTC):** 2026-08-21
**Question asked:** is the immutable `v0.1.0-rc4` candidate fit to become the MVP-1 release candidate?

**Outcome: FAIL**

M1-T18 remains open. A release-blocking defect was found in the candidate's application content, so
the fix requires a change to a packaged image and therefore a new release candidate through normal
release governance. Everything else validated is recorded below, because most of the system passed
and that is worth knowing precisely.

---

## 1. Release identity — PASS

| | |
| --- | --- |
| Version | `v0.1.0-rc4` |
| Commit | `86d1033b366b75e2258cc10fd5be80a591bbfe8d` |
| Tag agreement | `git rev-parse v0.1.0-rc4^{commit}` matches the manifest exactly |
| learning-platform | `ghcr.io/skpandey15/ramals-learning-platform@sha256:5f04c3db1e7d4894005f78ae89213eb20ca97080c2e3e04df7af953097355e56` |
| web-ui | `ghcr.io/skpandey15/ramals-web-ui@sha256:d9aa1c00d33d000c539f4e6e134535ba2aecd2f7f222263a7919f135cf8d7fb6` |
| ramals-ai | `ghcr.io/skpandey15/ramals-ai@sha256:91f5cc91b87da94da4afa86d75b89ff5786f25f342078c9b5d70f53aed9f338c` |
| Mutable references | none — the only `:latest` / `:main` strings in `deploy/` are prose forbidding them |
| Deployed digests | all three verified on the running containers, not read from the manifest |

The validation control plane was current `main`; the artifacts under test were RC4's images. No
application image was rebuilt from `main`.

## 2. Fresh deployment — PASS

Deployed through the canonical `deploy/deploy-controller.sh` path onto an empty database (this
project's postgres volume was removed by exact name; no prune was run).

- Flyway: **25 migrations validated, schema `core` at version 024, all successful**, from empty
- State machine reached **HEALTHY**, recording RC4 as known-good including the AI image
- All **11 health gates passed**, including the AI-plane gates that had never previously executed

The rollback/hold contract was exercised for real rather than asserted. An earlier attempt failed
its health gates for an environmental reason (below); the controller transitioned
`DEPLOYING → FAILED → ROLLBACK → RELEASE_HELD`, recorded the version as held, and refused to
redeploy it automatically. That is the documented behaviour, observed end to end.

## 3. Authentication and authorization — PASS

`scripts/validation/keycloak-e2e.py` against the deployed Keycloak:

- learner denied the admin surface (403) and the MFA-gated admin path (403)
- protected endpoints require authentication (401 unauthenticated, verified for both real and
  non-existent paths)

`scripts/validation/workload-identity-e2e.py` (M1-ADR-003):

- workload token carries `aud: ramals-ai`, is issued to `ramals-core-workload`, and represents a
  service account rather than a person
- a real learner token does **not** carry the `ramals-ai` audience
- the password grant is refused for the workload client
- the AI plane refuses an unauthenticated agent call (401)

AI workload identity is machine-to-machine and bounded. **PASS.**

## 4. Canonical learner journey — FAIL

The deterministic half passed completely, under a real Keycloak-issued learner token:

| Step | Result |
| --- | --- |
| diagnostic attempt created | 201 |
| retry with the same idempotency key returns the same attempt | same id, 200, not a fresh creation |
| attempt items served | 200 |
| answer key never leaves the server | verified |
| submission completes and attempt is COMPLETED | 200 |
| per-skill scores returned | verified |
| mastery map readable, mastery recorded | 200 |
| recommendations readable, a recommendation produced | 200 |
| progression readable | 200 |
| error path carries a support code | verified |

**The AI half of the journey does not exist in the candidate.** See §10.

## 5. Agent plane — PARTIAL

The plane itself deploys, authenticates and reports correctly:

```
service: ramals-ai            environment: test        aiEnabled: True
modelRoute: ci-fake           routeTableVersion: ROUTE_TABLE_V1
agents: ['DIAGNOSTIC', 'TUTOR', 'ASSESSMENT', 'ADAPTATION']
authority: NON_AUTHORITATIVE
```

`authority: NON_AUTHORITATIVE` is the ADR contract — AI output is proposal, not authority — asserted
by the running service rather than inferred from code. The backend logs
`"AI tutoring client configured"`, so the core is genuinely wired to the plane.

What could not be validated is the plane's use **through the product**, because no product path
reaches it. See §10.

## 6. Durability and correlation — PARTIAL

- `interactionId` and `traceId` are present on responses and in structured logs, and a support code
  from an error response locates the request in the backend log stream — verified directly while
  diagnosing §10
- idempotent replay of a diagnostic attempt returns the original attempt rather than creating a
  second
- `ai_execution` persistence **could not be exercised**: nothing in the product invokes an agent, so
  no execution rows are produced by a learner journey

## 7. Failure paths — PARTIAL

| Path | Result |
| --- | --- |
| unauthenticated agent call to the AI plane | 401 — PASS |
| invalid/absent workload audience on a learner token | rejected at the internal boundary — PASS |
| duplicate/idempotent request | same attempt returned — PASS |
| deployment health-gate failure | rollback and `RELEASE_HELD` — PASS |
| backend readiness independent of the AI plane | PASS |
| AI timeout / plane unavailable, end to end | **not exercisable** — no product path calls an agent |
| rate limiting | not re-validated here; covered by the R1 calibrated baseline and its dedicated suites |

## 8. Security and release gates — PASS (control plane)

`deploy/health-gates.sh` all gates green. The repository's required CI — backend, security,
governance, image releaseability, Gitleaks — is green on `main` at the control-plane commit. Those
gates test the control plane and the source tree; they did not catch §10, which is the point of
§10.

## 9. Environment notes, not candidate defects

Two local-environment artifacts were encountered and are recorded so they are not mistaken for
candidate behaviour:

- **Host port 8081 is owned by an unrelated k3d cluster.** Keycloak's published port was remapped to
  18081. In-network topology and the OIDC issuer were unchanged.
- **Docker Desktop on this host does not forward two of the four published ports** (8080 and 18081)
  although it forwards 8000 and 5173 and holds all four listeners in the same proxy process. Every
  service answered correctly from inside the compose network. All validation was therefore run
  in-network, which is the documented invocation for these drills. The first health-gate failure and
  the resulting `RELEASE_HELD` were caused by this, not by RC4.

## 10. The release-blocking defect

**The shipped web UI calls an endpoint the shipped backend does not serve.**

`web-ui/src/learning/tutorApi.ts` issues:

```
POST /api/v1/tutor/explain
```

RC4's backend has no such mapping. Proven against the running deployment with a real learner token
and a canonical UUIDv7 interaction id — from the backend's own log:

```
org.springframework.web.servlet.resource.NoResourceFoundException:
  No static resource api/v1/tutor/explain for request '/api/v1/tutor/explain'
```

The learner receives HTTP 500. Three of the four agent capabilities have no consumer anywhere in
the backend outside the `ai` package:

| Capability | Consumers outside `ai` |
| --- | --- |
| `TutorService` | **0** |
| `AdaptationService` | **0** |
| `DiagnosticPort` | **0** |
| `AssessmentPort` | 1 — `AssessmentCandidateIntakeService`, a content-intake path, not the learner journey |

`TutorService` is a live `@Service` bean, correctly constructed and wired to the AI plane, that
nothing calls. The board records **M1-T08 "Spring + UI Tutor integration" as done**; the client half
shipped and the server half did not.

**Secondary defect, same investigation:** an *authenticated* request to any unmapped path returns
`500 UNEXPECTED_ERROR` rather than 404. Unauthenticated requests correctly return 401. A missing
route is reported to the learner as an internal failure.

Both defects are in application content packaged into the backend image, so correcting them requires
a new candidate. **RC5 is justified**, through normal release governance, and only after the fix is
reviewed.

## 11. Control-plane fix made during T18

The canonical deployment topology defined no `ramals-ai` service — the plane was published, scanned,
digest-pinned and validated by the controller, and then deployed by nothing. `health-gates.sh`
skipped it and reported the deterministic core unaffected, which was true and passing, so the gap was
invisible.

`compose.deploy.yml` now defines the plane behind an `ai-plane` profile that `deploy-controller.sh`
activates when the manifest pins a `ramals-ai` digest, and the controller points the core at it. The
manifest decides the topology. A manifest without the component still deploys the core alone.

This changes no image content, so RC4's identity is unaffected — it is what made §5 observable at
all. Without it the AI-plane gates would have skipped again and the plane would have looked absent
rather than unreachable.

## Known non-blocking technical debt

- **TD-R1-02** — FAIL-baseline schema: fixed; the verdict is now a required field
- **TD-R1-03** — baseline `host.jvm` / `db_version` describe the load generator on a two-host run;
  partially fixed, structural half outstanding
- `deploy-controller.sh` runs under `set -euo pipefail`, so a failed `up` exits at that line leaving
  the state file at `DEPLOYING` with no `FAILED` transition. Observed; the exit status is honest and
  the next run re-attempts, so this is a rough edge rather than a defect

## Performance basis

Calibrated performance is not re-established here. It rests on the R1 baseline recorded in
[r1-calibrated-baseline.md](r1-calibrated-baseline.md) — `v0.1.0-rc4`, `perf-standard-01`, production
rate-limit policy, 0.00% failures across 12,455 requests. Nothing in this validation invalidates it:
no runtime artifact changed, and the defect in §10 concerns a route that never existed and therefore
was never exercised by the benchmark.

## Disposition

**Outcome: FAIL**

M1-T18 stays open and the board is unchanged. RC4 is not fit to become the MVP-1 release candidate:
its own web UI cannot reach tutoring, and the agent plane that defines MVP-1 has no product surface.
