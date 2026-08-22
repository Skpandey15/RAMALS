# MVP-1 final release reconciliation — `v0.1.0-rc8`

The final reconciliation of M1-T18 against the candidate actually being released. It supersedes the
scope of the M1-T18 record, which validated `v0.1.0-rc7`, and states the release decision.

**Outcome: PASS_WITH_ACCEPTED_DEBT**

`v0.1.0-rc8` is qualified as the MVP-1 release candidate, carrying four accepted debt items. Every
one is enumerated in §4 with its production impact and why accepting it does not violate an MVP-1
exit criterion. Nothing is waived silently.

## 1. Candidate identity

| Item | Value | Result |
| --- | --- | --- |
| Tag | `v0.1.0-rc8` | ✅ |
| Commit | `0c01c8518abae97bf986700a9af616e17a656aea` | ✅ |
| Approved manifest | `deploy/desired-version.json` → `v0.1.0-rc8` @ `0c01c85` | ✅ |
| learning-platform | `sha256:cc1af7a112f3f108fd750b646ab5c32ee2fb030adb6139a7836067b8c91fd73f` | ✅ running == approved |
| web-ui | `sha256:29fb2685818dc4394d51a3ad4699f1b516edc5dbeec3ecf840d8f54b8d05803a` | ✅ running == approved |
| ramals-ai | `sha256:5c0809289f2d02f5a1f62979ddf6f9f0fa92d58c31d21cadb8cd83768b12692e` | ✅ running == approved |
| Schema | 25 migrations applied, latest `V024`, 0 failed | ✅ |
| Working tree | clean | ✅ |

No candidate-identity mismatch. The STOP condition is not met.

`main` is ahead of the tag by `#112` and `#113`. Those changed `deploy/`, `docs/` and `scripts/ci/`
only — **no image content** — so the RC8 artifact is unaffected by them. The deployment controller
and health gates are host scripts rather than image content, so `#113`'s fix is live without a new
candidate.

## 2. Why the M1-T18 record's scope had to be reconciled

M1-T18 passed against `v0.1.0-rc7`. RC7 was later found to ship without the provider SDK, so no live
model route on it could dispatch at all. RC7 is superseded and must never be deployed.

The reconciliation rests on one checkable fact rather than on judgement:

```
git diff --name-only 98aedf8..v0.1.0-rc8 -- learning-platform/src web-ui/src
→ 0 files
```

**No backend or web-ui source changed between RC7 and RC8.** The entire functional delta is in
`ramals-ai`: the provider extra is installed, a live route is refused at startup when its SDK is
absent, and each live route approves a second vendor. The images were rebuilt, so the bytes and
digests differ, but the deterministic core's behaviour is the same source.

So RC7-scoped evidence for the deterministic core transfers on a stated basis, and the one criterion
that cannot transfer — the agent plane — is exactly the thing that changed, and has RC8-scoped
evidence that is stronger than RC7's.

## 3. Criterion-by-criterion reconciliation

Evidence keys: **[T18]** `m1-t18-e2e-validation.md` (RC7-scoped) · **[RQ]**
`rc8-requalification.md` · **[TD2]** `td-t18-02-closure.md` · **[FQ]** the final qualification run
recorded in §5 below.

| # | Criterion | Evidence | Result | TD / Exception |
| --- | --- | --- | --- | --- |
| 1 | Release identity — immutable digests, manifest agreement | **[FQ]** §1, digests re-verified on the gated path | **PASS** | — |
| 2 | Preflight and deployment via the controller state machine | **[RQ]** `APPROVED → DEPLOYING → HEALTHY`, exit 0 | **PASS** | — |
| 2b | Fresh deployment from an empty database | **[T18]** on RC7 | **PASS** *(RC7 basis)* | Not re-executed on RC8. Migrations are byte-identical (`V024` both) and no backend source changed, so the property is inherited rather than re-proven. Stated, not waived. |
| 3 | Authentication and authorization | **[TD2]** 22-check `keycloak-e2e` drill on RC8 under a real Keycloak token — 401 unauthenticated, 401 forged, 200 real, 403 admin surface, 403 MFA-gated | **PASS** *(RC8-scoped)* | — |
| 4 | Canonical learner journey | **[TD2]** attempt created, idempotent replay, items served, answer key never leaves the server, submission completes, mastery recorded, recommendations produced, progression readable | **PASS** *(RC8-scoped)* | — |
| 5 | Agent plane | **[RQ]** liveness, readiness, workload-identity 401, route-table smoke · **[TD2]** live model through `AdaptationProposalGate` | **PASS** *(RC8-scoped)* | RC7 evidence for this criterion is obsolete by construction — this is the component that changed. |
| 6 | Durability and provenance | **[TD2]** 5 `ai_execution` rows, 5 `STARTED` + 5 `SUCCEEDED` `ai_execution_event` rows, correlation preserved, no payloads persisted | **PASS WITH ACCEPTED DEBT** | **TD-T18-01** (delivery not durable), **TD-RC8-02** (`model_id` NULL) |
| 7 | Failure-path regression | **[T18]** on RC7 | **PASS** *(RC7 basis)* | Not re-executed on RC8; zero backend source delta. Stated, not waived. |
| 8 | Security and release gates | **[FQ]** the `v0.1.0-rc8` tag build — nine jobs green including Trivy on all three images, gitleaks, pip-audit | **PASS** *(RC8-scoped)* | Trivy scanned the enlarged `ramals-ai` image, including the newly-added provider dependency tree. |
| 9 | Host-published port reachability | **[RQ]**, **[FQ]** — not exercised | **PASS WITH ACCEPTED DEBT** | **TD-RC8-01**. Service reachability is proven on the deployment network; the host-published transport for the two JVM services is not. See §5. |
| 10 | Adaptation chain through the proposal gate | **[TD2]** | **PASS** *(RC8-scoped)* | Closes TD-T18-02. |
| 11 | Performance baseline (R1) | `r1-calibrated-baseline.md` — `v0.1.0-rc4` on an attested `perf-standard-01` environment | **PASS** | **TD-R1-03** (host provenance), unchanged |
| 12 | Rollback smoke — "the rollback took effect in the running service" | **[FQ]** conditional on `AI_EXPECTED_ROUTE_TABLE`, which this deployment does not set | **NOT APPLICABLE** | The gate skips by design: an unset expectation is a deployment that is not rolling anything back. Recorded as not applicable rather than as a pass, because it did not execute. |

No criterion is FAIL.

## 4. Technical-debt reconciliation

### TD-T18-01 — adaptation comparison delivery is not durable

**Problem.** The comparison runs on a `@TransactionalEventListener(AFTER_COMMIT)`. The event lives in
memory between commit and listener completion, so a process failure in that window loses the
comparison with no record that it was lost.

**Evidence.** The deployed live-model run proved the *ordering* half in a real environment: five
`decision_record` rows committed at `10:51:23.549`, five dispatches started `10:51:30`–`10:51:31`.
Commit precedes dispatch. That is the isolation property holding outside a test — and it is
explicitly **not** durability. Ordering and durability are different guarantees, and only the first
is proven.

**Production impact.** A lost comparison costs one `ai_execution` row and one disagreement datapoint.
The learner's evidence, mastery, recommendation and progression are committed and served before the
listener runs, so nothing a learner or auditor depends on is affected.

**Blocks MVP-1?** No.

**Mitigation.** The deterministic recommendation is authoritative and complete without the agent.
Losses are bounded to research/observability data.

**Target phase.** MVP-2 — durable delivery via a transactional outbox or a broker.

**Why accepting it does not violate an exit criterion.** No MVP-1 exit criterion requires durable
delivery of non-authoritative research data. It would be unacceptable for anything a learner or
auditor depends on, and it is not used for that.

### TD-RC8-01 — JVM services bind IPv6 only, so host-published ports are not portable

**Problem.** The backend and Keycloak listen on the IPv6 wildcard and never on IPv4, which is the JVM
default when `java.net.preferIPv4Stack` is unset. On a runtime whose forwarder provides no
host-reachable IPv4 path, their published ports are unreachable while the services are healthy.

**Evidence.** Isolated with a controlled pair of containers on the same host, identical image and
publish syntax, differing only in bind family: IPv6-only unreachable, IPv4 returned 200. Two
competing explanations were tested and disproved — a stale forwarder (the condition survived a full
container-engine restart with a new forwarder process) and multi-homing (a deliberately multi-homed
probe was reachable). `web-ui` (nginx) and `ramals-ai` (uvicorn) bind `0.0.0.0` and are unaffected.

**Separating artifact correctness from portability.** The identical condition reproduces on **RC7**,
which predates every RC8 change. It is therefore a property of the local Windows container runtime
and the JVM's default bind family, **not** a defect in the RC8 artifact and not a regression. RC8's
services are correct: they serve on the deployment network, their container healthchecks pass, and
all functional gates pass against them.

**Production impact.** None on a runtime that routes IPv4 to containers, which is the normal Linux
case. On a runtime that does not, host-published access to the two JVM services fails while the
services are healthy — a deployment that looks broken from outside and is not.

**Blocks MVP-1?** No, for the release decision. It does block host-port-based qualification *on this
host*, which is why §5 records what was and was not tested.

**Mitigation.** Qualification executed on the deployment network. The limitation is recorded in
`rc8-requalification.md` as a limitation, not as a resolved condition.

**Target phase.** A deliberate decision before production deployment on any runtime whose IPv4
behaviour is not known.

**Why accepting it does not violate an exit criterion.** No MVP-1 exit criterion requires
host-published port reachability on a developer workstation. The criterion the gates exist to prove —
that the services are healthy, authenticated and correctly wired — is proven over the network the
deployment actually uses.

### TD-RC8-02 — `ai_execution` cannot establish which concrete model produced a response

**Problem.** `ai_execution.model_id` is NULL, including on the successfully qualified live-provider
execution. `model_route` identifies only the *logical* route, and since RC8 two vendors are approved
behind every live route.

**Evidence.** All five qualified executions: `model_route = adaptation-default`, `model_id = NULL`.

**Production impact.** Route-level provenance exists and is persisted. Concrete provider/model
provenance **is not persisted** and must not be claimed. Reconstructing which vendor answered means
correlating against service logs that rotate. This matters for evaluation comparability and for
incident analysis that needs to attribute an output to a vendor.

**Blocks MVP-1?** No.

**Mitigation.** Only one route is live at a time and the resolved route table version — which does
name the model — is logged at startup and on every call, and is reported by
`/internal/v1/capabilities`.

**Target phase.** MVP-2. The design should persist at minimum the resolved provider, the resolved
`model_id`, the route identifier, and the route/configuration version or equivalent immutable routing
provenance.

**Why accepting it does not violate an exit criterion.** No MVP-1 exit criterion requires
concrete-model provenance, and TD-T18-02's own closure condition named `model_route`, not `model_id`.
RC8 made this gap *material* by approving a second vendor; it did not introduce it.

### TD-R1-03 — baseline host provenance describes the wrong machine

**Problem.** Preserved exactly as previously documented: `run-baseline.sh` recorded `host.jvm` and
`host.db_version` by probing the machine running the script rather than the system under test, so on
a two-host run those fields describe the load generator.

**Evidence.** As recorded on the R1 board entry. Unchanged by this reconciliation.

**Production impact.** None on the platform. It is a fidelity defect in the baseline record's
host-provenance fields.

**Blocks MVP-1?** No — R1 is closed on a single-host canonical run where the fields are correct.

**Mitigation.** As previously documented; status remains 🟡 partially fixed.

**Target phase.** Unchanged from its existing entry.

**Why accepting it does not violate an exit criterion.** R1's exit criterion is a calibrated baseline
on an attested environment, which was met. Host-provenance fidelity on two-host runs is not an MVP-1
exit criterion.

## 5. Final qualification — what was and was not tested

Executed with the canonical `deploy/health-gates.sh`, **unmodified and piped in verbatim**, inside a
container attached to `ramals-deploy_edge`, with only the three service URLs and `AI_URL` overridden
to the deployment network's DNS names. RC8 was not modified to make host forwarding work.

Running digests were verified against the published RC8 artifacts before any gate ran.

**Tested and passed:**

| Gate | Result |
| --- | --- |
| backend liveness | ✅ |
| backend readiness | ✅ |
| backend health (db) | ✅ |
| oidc issuer (`jwks_uri` present) | ✅ |
| web ui | ✅ |
| ai plane liveness | ✅ |
| ai plane readiness | ✅ |
| smoke: AI agent endpoint requires workload identity (401) | ✅ |
| smoke: AI plane reports its route table (`ROUTE_TABLE_V1`) | ✅ |
| backend readiness is independent of the AI plane | ✅ |
| smoke: protected endpoint requires authentication (401) | ✅ |

**Not tested:**

* **Host-published ports for the two JVM services** — TD-RC8-01. Backend and Keycloak were reached
  over the deployment network, never through their published host ports. `web-ui` and `ramals-ai`
  publish on IPv4 and are unaffected, but were also gated over the network for consistency.
* **Gate 12, the rollback smoke** — conditional and not applicable; see §3.
* **Fresh deployment from an empty database on RC8** — see criterion 2b.
* **The live provider route** — deliberately. The TD-T18-02 evidence is final and was not
  regenerated. The plane runs `ci-fake`; `RAMALS_AI_PROVIDER_API_KEY` is empty in the container and
  `RAMALS_AI_MODEL_PINS` is `{}`, so no billable route is enabled.

## 6. Release decision

**Outcome: PASS_WITH_ACCEPTED_DEBT**

Accepted debt, in full:

| Item | Status | Blocks MVP-1 |
| --- | --- | --- |
| **TD-T18-01** — adaptation comparison delivery is not durable | 🟡 accepted | No |
| **TD-R1-03** — baseline host provenance describes the wrong machine | 🟡 partially fixed | No |
| **TD-RC8-01** — JVM services bind IPv6 only; host-published ports not portable | 🟡 open | No |
| **TD-RC8-02** — `ai_execution` cannot establish the concrete model | 🟡 open | No |

Closed and not carried: TD-R1-01, TD-R1-02, **TD-T18-02**.

## 7. Production-readiness

These are two different statements and are kept apart deliberately.

**MVP-1 release qualification: PASS_WITH_ACCEPTED_DEBT.** `v0.1.0-rc8` is qualified as the MVP-1
release candidate. Its identity is immutable and verified, its deterministic core is complete and
authoritative, its security boundaries are enforced by the running services rather than asserted, and
its agent plane is proven end to end through the proposal gate against a live model.

**Production-hardening debt is unresolved.** The strongest wording the evidence supports is:

> `v0.1.0-rc8` is **qualified for MVP-1 release** and is **approved for the shared dev environment**.
> It is **not certified production-ready**. Four items of production-hardening debt remain open, and
> two of them — TD-RC8-01 and TD-RC8-02 — should be settled before a production deployment on an
> unfamiliar runtime, or before any evaluation or incident analysis depends on attributing an output
> to a vendor.

MVP-1 must not be described as "fully production-ready". It is a qualified release candidate with
enumerated, accepted, non-blocking debt.
