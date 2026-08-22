# M1-T18 — MVP-1 end-to-end validation of the release candidate

**Date (UTC):** 2026-08-22
**Question asked:** is the immutable `v0.1.0-rc7` candidate fit to become the MVP-1 release candidate?

**Outcome: PASS**

Every release-blocking gate passes against a fresh deployment of `v0.1.0-rc7`.

**What this PASS does and does not cover, decided explicitly rather than inferred.** One leg of the
adaptation chain is not proven: the request reaches the AI plane and the execution is durably
recorded, but no proposal reaches `AdaptationProposalGate`, because the shipped deterministic
`ci-fake` route returns a plain string that every agent validator rejects by design. That limitation
was reviewed and **accepted for MVP-1** as TD-T18-02 (§11), on the grounds that the agent is provably
reachable from a real learner journey, the deterministic recommendation is unaffected by the agent
failing, the failure is durably recorded with correlation, the gate's own logic is unit-tested, and
no candidate defect is implicated.

It is recorded here at the top rather than in a footnote because a reader deciding whether to ship
should not have to reach §11 to find the one thing this run could not demonstrate.

---

## 1. Release identity — PASS

| | |
| --- | --- |
| Version | **`v0.1.0-rc7`** |
| Commit | `98aedf898a06289cb3a35555e729d50817a36258` |
| learning-platform | `ghcr.io/skpandey15/ramals-learning-platform@sha256:827f81b63ee04f863af1bcb077b7092379022cc31c54365cd1fd9e0549289c0e` |
| web-ui | `ghcr.io/skpandey15/ramals-web-ui@sha256:d1935f3398a128c779803f40e39abb3cfd6ddeac8262b78d6334e1690312a8f6` |
| ramals-ai | `ghcr.io/skpandey15/ramals-ai@sha256:91552946a25535c35b88ef18a9ad1929a231d7afab7de9fd360fe3b7521b3d10` |

Digests were verified on the **running containers**, cross-checked against GHCR independently of the
pipeline's own report, and match `deploy/desired-version.json`. No `:latest` or `:main` reference
exists in the deployment configuration; the only occurrences are prose forbidding them.

Superseded candidates and why: **rc4** carried the pre-TD-R1-01 rate-limit configuration and lacked
the tutor endpoint. **rc5** fixed the tutor endpoint and 404 semantics but was never promoted.
**rc6** added adaptation reachability and then failed §6 below. Each was replaced rather than
patched in place.

## 2. Preflight and fresh deployment — PASS

**Preflight on the approved SHA.** Local `HEAD` equalled `origin/main` at
`98aedf898a06289cb3a35555e729d50817a36258` with a clean tree, and every required gate ran green
before anything was cut or deployed:

| Suite | Result |
| --- | --- |
| `test` | 472, 0 failures |
| `architectureTest` | 33, 0 failures |
| `governanceTest` | 136, 0 failures |
| **`integrationTest` against real PostgreSQL** | **108, 0 failures, 0 skipped** |
| Security CI scripts | 7 of 7, exit 0 |
| ramals-ai contract + unit | 307, 0 failures |

The `ai_execution` PostgreSQL round-trip test added by #109 was not merely run but **proved to
bite**: restoring the `Instant` bind fails it with the same `BadSqlGrammarException` that made rc6
unshippable. A guard that cannot fail is not a guard.

One caveat worth recording, because it looks alarming and is not: running `test` and
`integrationTest` concurrently against a single database produces deadlocks, connection exhaustion
and concurrent-Flyway errors. The build orders them deliberately (`integrationTest.mustRunAfter`)
because they reset and re-provision the same schema. Run sequentially against a clean database they
are green.

`v0.1.0-rc5` and `v0.1.0-rc6` remain immutable and unmoved; rc6 is recorded as non-shippable.

## 2b. Fresh deployment — PASS

Deployed through the canonical `deploy/deploy-controller.sh` path onto an empty database.

- Flyway: **25 migrations validated, schema `core` at v024, all successful**, from empty
- State machine: `APPROVED → DEPLOYING → HEALTHY`, recording rc7 as known-good including the AI image
- **All 11 health gates passed**, including the AI-plane gates
- Restarts and OOM kills: **zero across all five services**
- **No unintended public exposure.** Every published port binds `127.0.0.1` only — backend 8080,
  web-ui 5173, AI plane 8000, Keycloak 18081 — and PostgreSQL publishes nothing at all.
- **Issuer agrees across every component**: backend `RAMALS_OIDC_ISSUER_URI`, the AI plane's
  `RAMALS_AI_OIDC_ISSUER`, and the issuer Keycloak itself advertises are all
  `http://keycloak:8080/realms/ramals`.

The rollback and hold contract was exercised for real earlier in this exercise, not asserted: a
health-gate failure drove `DEPLOYING → FAILED → ROLLBACK → RELEASE_HELD`, and the controller refused
to redeploy the held version automatically.

## 3. Authentication and authorization — PASS

`scripts/validation/keycloak-e2e.py`, **26 checks, all passing**:

- a real Keycloak-issued token carries the `ramals-api` audience, the LEARNER realm role and the
  `learner_id` claim, and its issuer matches the backend's expectation
- unauthenticated requests are rejected (401); a forged token is rejected (401)
- a learner is denied the admin surface (403) and the MFA-gated admin path (403)

`scripts/validation/workload-identity-e2e.py` (M1-ADR-003), all passing:

- the workload token carries `aud: ramals-ai`, is issued to `ramals-core-workload`, and represents a
  service account rather than a person
- a real learner token does **not** carry the `ramals-ai` audience
- the password grant is refused for the workload client
- the AI plane refuses an unauthenticated agent call (401), verified again by direct probe

## 4. Canonical learner journey — PASS

The deterministic diagnostic flow, under a real learner token, end to end:

| Step | Result |
| --- | --- |
| diagnostic attempt created | 201 |
| replay with the same idempotency key returns the same attempt, not a new one | 200 |
| attempt items served | 200 |
| **answer key never leaves the server** | verified |
| submission completes; attempt is COMPLETED | 200 |
| per-skill scores returned | verified |
| mastery map readable; mastery recorded | 200 |
| recommendations readable; a recommendation produced | 200 |
| progression readable | 200 |
| error path carries a support code | verified |

Spring remains the deterministic authority throughout. The recommendation a learner receives is
produced by `RecommendationPolicy` and is unchanged by anything the AI plane does or fails to do.

## 5. Agent plane — PASS

```
agents      : DIAGNOSTIC, TUTOR, ASSESSMENT, ADAPTATION
authority   : NON_AUTHORITATIVE
routeTable  : ROUTE_TABLE_V1
modelRoute  : ci-fake
```

`authority: NON_AUTHORITATIVE` is the ADR contract asserted by the running service rather than
inferred from source. The plane deploys, authenticates, enforces the workload boundary, and reports
its route table so a prompt rollback is verifiable.

**Tutor** is reachable: `POST /api/v1/tutor/explain` returns 200 with the discriminated outcome the
web client parses, carrying the support code the learner was shown. **Adaptation** is reachable: a
learner submission dispatches the comparison. Assessment remains an authoring-side capability and
Diagnostic-agent orchestration is out of MVP-1 scope — both recorded in
[the integration contract](../../architecture/mvp1-agent-integration-contract.md).

## 6. Durability and provenance — PASS

The gate that made rc6 unshippable, now proven against real PostgreSQL rather than inferred from
logs. A single learner journey writes **5 `ai_execution` rows**; across the qualification run, 10.

| Assertion | Result |
| --- | --- |
| execution id present | true |
| interaction id present | true |
| request id present | true |
| agent type present | true |
| status present | true |
| `started_at` parses as a timestamp | true |
| `completed_at` parses as a timestamp | true |
| `completed_at >= started_at` | true |
| timestamps are plausible, not epoch or garbage | true |
| duplicate `request_id` values | **0 of 10** |

**Correlation survives the post-commit boundary, proven across two ledgers**: all 10
`core.ai_execution` rows join to a `ledger.decision_record` on `interaction_id`. A support code a
learner was shown therefore locates both the authoritative decision and the AI execution that
observed it.

**No secrets, tokens or raw payloads are persisted, structurally.** `core.ai_execution` has **no
free-text column at all** — every text column is an identifier, a code, a status or a digest — so
there is nowhere for a prompt, a completion or a credential to be written. Zero JWT-shaped values
anywhere in the table.

Why this had to be proven rather than assumed: on rc6 both write paths bound `java.time.Instant`
straight to JDBC, which PostgreSQL's driver refuses, so no row could be written on success *or*
failure — and the failure-recording path failed identically, leaving nothing behind to notice. It
survived because the only writer was a path no controller reached and the covering tests ran on H2,
which accepts the type PostgreSQL rejects.

## 7. Failure-path regression — PASS

| Contract | Evidence |
| --- | --- |
| AI failure does not roll back deterministic learner state | `evidence=10, decision_records=10, recommendations=10` while `ai_failed=10`. **Every AI execution failed and no deterministic state was lost.** |
| AI disagreement does not change the deterministic recommendation | the gate returns the deterministic decision in every case; asserted by `AdaptationReachabilityTests` with a deliberately disagreeing proposal |
| a failed authoritative transaction causes no adaptation dispatch | asserted by publishing inside a transaction that rolls back — the agent is never called |
| replay does not create a duplicate adaptation execution | **0 duplicate `request_id`s**; the request id is derived from the decision, and a replayed submission finds the attempt COMPLETED and never recomputes |
| correlation survives the post-commit boundary | 10 of 10 rows join to a decision record on `interaction_id` |
| unauthenticated agent call to the AI plane | 401 |
| authenticated request to an unmapped route | 404, not 500 |
| deployment health-gate failure | rollback and `RELEASE_HELD`, exercised for real earlier in this exercise |

The AI-unavailable path is not hypothetical here — it is the path this entire run took, and the
platform behaved exactly as M1-T11 specifies.

## 8. Security and release gates — PASS

Required CI green on the release ref: Backend, Contract, Frontend, Python and Security CI, plus all
three image-releaseability jobs and Gitleaks. Governance suite green. `perf-standard-01` attestation
and the calibrated baseline are unchanged and remain valid — see
[r1-calibrated-baseline.md](r1-calibrated-baseline.md). No runtime path changed since that baseline
in a way that invalidates it; the defects fixed since concerned routes that had never been
exercised.

## 9. Environment notes, not candidate behaviour

Recorded so they are not mistaken for defects, and because each cost a cycle:

- **Host port 8081 is owned by an unrelated k3d cluster.** Keycloak's published port was remapped;
  in-network topology and the issuer were unchanged.
- **Docker Desktop does not forward two of the four published ports on this host** while forwarding
  the other two from the same proxy. All validation was therefore run in-network, which is the
  documented invocation for these drills anyway.
- **`docker compose up -d` does not rebuild a locally-built image that already exists.** A five-day
  stale Keycloak image was deployed with a realm predating `ramals-core-workload`, and every health
  gate passed while the workload client was absent. `--build` is required for the locally-built
  services.
- **Restarting the backend outside `deploy-controller.sh` silently drops `RAMALS_AI_BASE_URL`**,
  because the controller is what exports it. The backend then starts healthy with no AI plane
  configured. Both belong in the runbook.

## 10. Defects found and fixed during M1-T18

Each was in a code path no deployment had ever executed, which is the pattern worth carrying
forward:

1. **The tutor endpoint did not exist.** The shipped web client posted to `/api/v1/tutor/explain`;
   the backend served no such route and returned 500. The service, the client and the AI wiring all
   existed — nothing joined them.
2. **Nothing consumed `AdaptationService`.** M1-T11's port, client, service and gate were complete
   and unreachable, so no learner journey wrote execution evidence at all.
3. **Every `ai_execution` write failed against PostgreSQL.** See §6.
4. **Unmapped authenticated routes returned 500 rather than 404**, making "not here" and "we failed"
   the same response, and attributing path-probing traffic to `UNEXPECTED_ERROR`.
5. **The AI plane had no compose service.** It was published, scanned, digest-pinned and validated by
   the controller, then deployed by nothing, while the health gate skipped it and passed.

## 11. Known non-blocking technical debt

- **TD-T18-02 — the adaptation chain is proven as far as the AI plane, not through the proposal
  gate. Accepted explicitly for MVP-1.**

  The canonical chain is `learner event → deterministic recommendation → commit → AFTER_COMMIT
  listener → AdaptationService → ramals-ai → AdaptationProposalGate → ai_execution`. Every link is
  proven **except the gate**: the plane returns `UNPROCESSABLE_PROPOSAL`, and `AdaptationService`
  catches that before the gate is reached. The same is true of the Tutor agent.

  The cause is the shipped deterministic route, not a defect. `FakeProvider.complete()` returns

  ```
  f"[ci-fake:{request.model}] deterministic completion {digest[:16]}"
  ```

  — a plain string, not JSON. Every agent validator rejects it after the bounded repair budget, by
  design: the fake exists to make the transport, budget and failure paths exercisable without a
  provider, not to imitate a model's output. Two things make this easy to misread, and both were
  misread during this validation before the shipped provider was examined: a comment stating that
  `ci-fake` "serves all four agents" describes the *route table*, and `test_adaptation_agent.py`
  passes because it injects its own stub provider constructed with a real JSON payload.

  **What is therefore proven:** the agent is reachable from a real learner journey, the request
  arrives at the plane authenticated as the core workload, the deterministic recommendation is
  unaffected by the agent failing, and the failure is durably recorded with correlation. **What is
  not proven:** that a model's proposal passes the gate, and consequently `agent_run_id`,
  `prompt_template_id`, `prompt_version` and `model_route` are NULL on every row, because those
  fields come from a proposal that never existed.

  **Closing it needs a run with a real `RAMALS_AI_MODEL_ROUTE` and a provider credential.** It is
  accepted here because the gate's own logic is unit-tested, the authority contract it enforces is
  proven by the failure path, and no candidate defect is implicated — but MVP-1 should not be called
  production-ready until a live model has been through it.

- **TD-T18-01 — adaptation comparison delivery is not durable.** `AFTER_COMMIT` isolates the AI call
  from the authoritative transaction but is not durable delivery; a process failure between commit
  and listener completion loses the comparison silently. Accepted because the comparison is research
  input, not learner-visible behaviour.
- **TD-R1-02 / TD-R1-03** — baseline verdict schema and two-host host-provenance, both recorded on
  the release board.
- **Diagnostic-agent orchestration and learner-facing Assessment evaluation are out of MVP-1 scope**
  by decision, not omission. Both are recorded in the integration contract with the seven-question
  analysis behind them.

## Disposition

**Outcome: PASS**

`v0.1.0-rc7` is fit to be the MVP-1 release candidate. The deterministic core is complete and
correct, the agent plane is deployed, secured and reachable from the learner journey, AI output is
non-authoritative in code and in behaviour, and execution provenance is durably written and
correlated.
