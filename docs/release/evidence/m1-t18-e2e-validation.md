# M1-T18 — MVP-1 end-to-end validation of the release candidate

**Date (UTC):** 2026-08-22
**Question asked:** is the immutable `v0.1.0-rc7` candidate fit to become the MVP-1 release candidate?

**Outcome: PASS**

Every release-blocking gate passes against a fresh deployment of `v0.1.0-rc7`. One limitation is
recorded as non-blocking technical debt and is stated plainly in §11 rather than buried: a
*successful* agent proposal could not be observed, because this environment has no LLM provider
credential and the deterministic `ci-fake` route cannot produce one by design.

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

## 2. Fresh deployment — PASS

Deployed through the canonical `deploy/deploy-controller.sh` path onto an empty database.

- Flyway: **25 migrations validated, schema `core` at v024, all successful**, from empty
- State machine: `APPROVED → DEPLOYING → HEALTHY`, recording rc7 as known-good including the AI image
- **All 11 health gates passed**, including the AI-plane gates
- Restarts and OOM kills: **zero across all five services**

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

**15 `ai_execution` rows written by the learner journey**, with timestamps intact and every row
correlated by `interaction_id`.

This is the gate that failed on rc6 and is the reason rc7 exists. Both write paths bound
`java.time.Instant` straight to JDBC, which PostgreSQL's driver refuses outright, so no execution
row could be written on a real database — on success or on failure. The failure-recording path
failed identically, which is why nothing was left behind to notice.

It survived that long because the only writer was a path no controller reached, and the tests
covering it run on H2, which accepts the type PostgreSQL rejects. `AiExecutionProvenanceIntegrationTests`
had twelve tests against real PostgreSQL asserting the table's shape, constraints, retention and
redaction, and none of them wrote a row through the repository. It now does, and reads the
timestamps back.

`interactionId` and `traceId` propagate through the post-commit path into execution evidence and the
log stream, so a row is findable from the support code a learner was shown.

## 7. Failure paths — PASS

| Path | Result |
| --- | --- |
| unauthenticated agent call to the AI plane | 401 |
| learner token presented at the internal boundary | refused |
| duplicate / idempotent submission | same attempt returned, no second agent dispatch |
| AI plane unavailable or unable to answer | deterministic recommendation unchanged; failure recorded with a reason |
| authenticated request to an unmapped route | 404, not 500 |
| deployment health-gate failure | rollback and `RELEASE_HELD` |
| backend readiness independent of the AI plane | verified |

The AI-unavailable path is not a hypothetical here — it is the path this run actually took, and the
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

- **TD-T18-02 — agent proposal quality is unvalidated end to end.** Both Tutor and Adaptation return
  `UNPROCESSABLE_PROPOSAL` on the `ci-fake` route: the deterministic fake returns canned output that
  cannot satisfy the plane's validators, by design. Every `ai_execution` row from this run therefore
  records `AI_UNAVAILABLE`. What is proven is that the agent is reachable, that deterministic
  authority is preserved when it cannot answer, and that the failure is durably recorded with
  provenance — which is what the release gate asks. What is **not** proven is that a real model
  produces a valid, useful proposal. That needs a run with a real `RAMALS_AI_MODEL_ROUTE` and a
  provider credential, and should happen before MVP-1 is called production-ready.
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
