# RAMALS MVP-0 Release Candidate — v0.1.0-rc1

**Status: releasable as the deterministic adaptive learning baseline, with three documented
exclusions (§5).** This build is the scientific control against which later agentic versions are
measured.

| | |
| --- | --- |
| Version | `v0.1.0-rc1` |
| Commit | `36645cf` |
| Backend image | `ghcr.io/skpandey15/ramals-learning-platform@sha256:a31d830a…` |
| Web UI image | `ghcr.io/skpandey15/ramals-web-ui@sha256:e527dd43…` |
| Schema | Flyway `013` |
| Backend tests | 200 across 56 classes — 0 failures, 0 skipped |
| Frontend tests | 12 — lint clean, build succeeds |
| Release pipeline | green: build → push → SBOM → scan → provenance attestation |

Both images are addressed by immutable digest, scanned clean of fixable CRITICAL/HIGH findings, and
carry signed build provenance. `deploy/desired-version.json` is frozen at these digests.

## 1. Architecture conformance

| Principle | Conformance |
| --- | --- |
| Authoritative learner state outside the LLM | Yes — no AI runtime dependency exists in MVP-0 |
| Deterministic, versioned decisioning | Yes — `WEIGHTED_MASTERY_V1`, `EVIDENCE_CONFIDENCE_V1`, `RECOMMENDATION_POLICY_V1`, `PROGRESSION_POLICY_V1`, `DIAGNOSTIC_SCORING_V1`, all stamped on persisted records |
| Append-only evidence, snapshots, decisions | Yes — enforced by privilege (`42501`) **and** trigger (`55000`) |
| Versioned curriculum and assessments | Yes — published content immutable; attempts pin the assessment version |
| Correlation on every consequential write | Yes — `interactionId` on evidence, snapshots, decisions, session transitions, admin audit |
| Zero Trust: RBAC + object-level ownership | Yes — `/me` surface makes cross-learner access unaddressable |
| Least-privilege database identity | Yes — runtime holds SELECT/INSERT-only on `ledger` and `audit` |
| Build once / promote the same artifact | Yes — digest-addressed deployment, PR path cannot publish |
| Correctness over throughput | Yes — see ADR 0002 |

**Deviations are recorded as ADRs**, not silent: [0001](../adr/0001-learner-identity-from-oidc-subject.md)
(subject-based identity), [0002](../adr/0002-synchronous-adaptive-pipeline.md) (synchronous pipeline
and ADL), [0003](../adr/0003-migration-numbering-follows-implementation-order.md) (migration
numbering), [0004](../adr/0004-container-scanning-and-dependency-pinning.md) (scanning and pinning).

## 2. Traceability matrix

| Task | Delivered | Evidence |
| --- | --- | --- |
| T01/T01C | Repo skeleton, PR CI, required `ci-gate` | change-detection + gate green on every PR |
| T02 | Docker Compose, Keycloak realm | `infrastructure/docker` |
| T03 | Correlation, structured logs, Problem Details | `CorrelationContractTests`, `UuidV7Tests` |
| T04 | OIDC resource server, roles, MFA policy | `SecurityContractTests` |
| T05 | Schemas, roles/grants, Flyway baseline | `PostgresMigrationIntegrationTests`, `MigrationScriptContractTests` |
| T06 | Versioned curriculum + Kafka v1 graph | `Curriculum*Tests` |
| T07 | Learner domain, `/me` ownership | `LearnerApiContractTests`, `LearnerPersistenceIntegrationTests` |
| T08 | Assessment catalog, idempotent attempts | `Diagnostic*Tests` (201/200, one-active invariant) |
| T09 | Submission + deterministic scoring | `DiagnosticScorerTests`, submission persistence (rollback) |
| T10 | Immutable evidence ledger | `EvidenceLedger*Tests` (42501 + 55000) |
| T11 | Mastery engine | `WeightedMasteryCalculatorTests`, monotonic-version concurrency test |
| T12 | Evidence confidence + gating | `EvidenceConfidenceCalculatorTests`, `MasteryStatusPolicyTests` |
| T13 | Recommendation + DecisionRecord | `Recommendation*Tests` (reconstruction, immutability) |
| T14 | Progression, prerequisites, retention | `Progression*Tests` (regression preserves history) |
| T15 | Learning session state | `LearningSession*Tests` (optimistic conflict, resume) |
| T16 | React learner experience | `LearnerDashboard.test.tsx` (E2E slice, a11y, no web storage) |
| T17 | Admin/content minimum | `AdminContent*Tests` (role gating, audited rejections) |
| T18 | Security hardening | `NegativeAuthorizationTests`, `ZeroTrustPrivilegeIntegrationTests`, rate-limit tests |
| T19 | Observability and audit | `ObservabilityApiTests`, Grafana dashboard, runbook |
| T20 | Performance harness | `PerformanceHarnessTests` — harness verified, **baseline not captured** |
| T21 | CI/CD release completion | green release run; `test-deploy-controller.sh` (RELEASE_HELD) |
| T22 | End-to-end validation | `MvpZeroValidationTests` + backup/restore drill |
| T23 | Release candidate | this document |

## 3. Security evidence

- Negative authorization suite: unauthenticated 401, per-endpoint role denial, cross-learner
  IDOR/BOLA returns 404.
- Database privilege attacks: runtime denied UPDATE/DELETE on all `ledger`/`audit` tables (`42501`),
  denied DDL, Flyway history protected.
- Append-only triggers reject mutation even for the table owner (`55000`).
- Rate limiting: 429 with `Retry-After` and recovery, shed before authentication.
- Security headers: CSP, HSTS, `nosniff`, frame-deny, referrer policy, permissions policy.
- Secret hygiene: gitleaks over full history; no secrets in source, images or logs.
- Supply chain: SHA-pinned actions, PR path provably unable to publish, SBOM + Trivy gate +
  provenance attestation on every published image, nightly re-scan of deployed digests.

## 4. Known risks

| # | Risk | Severity | Mitigation / status |
| --- | --- | --- | --- |
| R1 | No calibrated performance baseline | **High** | Harness ready; must run on the authoritative fixed-spec environment before any SLA claim |
| R2 | No live deployment executed | Medium | Manifest now frozen at real digests; first pull-based deploy is the next action |
| R3 | Keycloak-issued token path untested end to end | Medium | Authorization proven with mock JWTs; realm mints required claims but no live-token test exists |
| R4 | Thresholds are uncalibrated | Medium | Mastery/confidence thresholds are engineering defaults, versioned so recalibration is traceable |
| R5 | Single-host, non-redundant dev topology | Low | Documented; no availability SLA claimed |
| R6 | `apk upgrade` reduces build reproducibility | Low | Bounded by per-commit rebuild and nightly re-scan (ADR 0004) |
| R7 | Superseded `/learners/{id}/profile` stub remains | Low | Retained for its T04 contract test; retire in MVP-1 (ADR 0001) |
| R8 | Driver pinned ahead of the Spring Boot BOM | Low | Remove once the BOM advances past 42.7.12 (ADR 0004) |

## 5. Exclusions — not claimed as passing

1. **Performance Definition of Done is not met.** No baseline has been captured on the authoritative
   environment; MVP-0 ships engineering objectives, not measured SLOs.
2. **Deployment Definition of Done is partially met.** The pipeline publishes attested artifacts and
   the release-hold state machine is proven in isolation, but no pull-based deployment has run
   against a live environment.
3. **Live-token authorization is unproven.** All authorization evidence uses mock JWTs.

## 6. MVP-1 entry criteria

MVP-1 (Python AI service, agent orchestration) may begin only when all of the following hold. **No
MVP-1 code is included in this release.**

1. **Baseline captured.** A performance baseline exists for `mixed-learning` and `diagnostic`
   (including ADL) on the authoritative environment, committed as machine-readable data. Without it
   there is no control to measure agentic versions against — this is the whole point of MVP-0.
2. **Deployment proven.** At least one pull-based deployment of an approved manifest has reached
   `HEALTHY`, and one deliberately bad version has been observed rolling back into `RELEASE_HELD`.
3. **Live-token authorization verified.** An end-to-end test drives a token actually issued by
   Keycloak, including the MFA-gated admin path.
4. **Risks R1–R3 closed or explicitly accepted** by a named owner.
5. **Deterministic control frozen.** The algorithm and policy versions in this release are treated as
   immutable; any change ships as a new version identifier so historical decisions stay reproducible.
6. **Boundary respected.** Python workloads receive their own workload identity and must not access
   `core`/`ledger` tables directly; authoritative mastery and progression stay in the deterministic
   engine, with agent output constrained by policy gates.

## 7. Release decision

**Recommended: release as `v0.1.0-rc1` — a release *candidate*, not a general availability build.**

The deterministic adaptive learning baseline is complete, tested, auditable and reproducible, and it
is fit to serve as the scientific control. It is **not** ready for a production availability or
latency commitment until R1–R3 are closed.
