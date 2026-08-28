# M2-ADR-017: Where Contract B durable state lives, and what of a model result may be stored

- **Status:** Accepted — 2026-08-27. Decides ownership and storage policy; authorizes no schema,
  no migration and no code (see §6).
- **Date:** 2026-08-27
- **Relates to:** M2-ADR-016 (which recorded both open questions this ADR closes), M2-ADR-001,
  M2-ADR-005, M2-ADR-008, M2-ADR-012, M2-ADR-014, M1-ADR-005, V023, V035, V036
- **Originates here.** Not part of the MVP-2 ADR package, and numbered into the sequence on the
  same basis as M2-ADR-016 and M1-ADR-011.

## Context

M2-ADR-016 settled the provider capability gate and granted asynchronous execution to Contract-B
routes. It deliberately left two prerequisites open, and recorded that acceptance authorized
neither schema nor traffic until they were decided:

1. **Where Contract B durable state lives.** The Contract B design document places a durable
   execution ledger inside RAMALS-AI. That service has no database driver, and the exclusion is
   asserted by a test rather than merely documented.
2. **Whether model output may be stored at all.** Recoverability requires it. `V023` and `V035` say
   it is structurally impossible to store in the existing ledger.

Both are now unblocked by the same fact: Contract A is complete. Its implementation merged in #160
and its T15 qualification passed S1–S4 on the frozen candidate
`b79df3b391ba04f972d08d740a06a42de23385d1`. Contract B no longer risks destabilising an unfinished
guarantee, and the recovery semantics it must not weaken are now demonstrated rather than intended.

## Decision

### 1. Contract B durable state lives in Spring/PostgreSQL under Flyway. RAMALS-AI stays stateless.

The AI plane gains **no database, no driver, no migration authority and no durable state of its
own**. Contract B's admission record, provider-execution handle, durable result, transition ledger
and reconciliation work queue are all owned by the platform, in the `core` schema, under
`ramals_core_migration`.

RAMALS-AI's Contract B surface is a **stateless durable-provider adapter**: it submits, reports
status, and returns results. It remembers nothing between calls. Every durable fact it produces is
handed back across the API boundary and written by Spring.

This is not a new decision so much as a refusal to reverse three existing ones. M2-ADR-001 makes
Spring/PostgreSQL authoritative. M2-ADR-012 places durable AI execution and provenance in execution
records, which are platform-owned. M2-ADR-008 confines the AI plane to transient execution. The
statelessness is enforced today by `ramals-ai/tests/unit/test_no_database_access.py` and by the
absence of any PostgreSQL driver from `ramals-ai/pyproject.toml`, whose comment names Flyway under
`ramals_core_migration` as the sole DDL authority for the shared database.

Giving the AI plane its own durable store would create a second writer of learner-derived state, a
second migration authority over data the platform is accountable for, and a second place a recovery
worker could disagree with the ledger. The design document's diagram is not wrong about what
Contract B needs; it is wrong about which process should own it.

### 2. Only a normalized result may be persisted, and only for as long as recovery needs it

**What may be stored:** the **normalized proposal** — the validated envelope conforming to the
committed `diagnostic-proposal.v1` schema, which is exactly what the Spring gate consumes. Plus
provider execution identifiers, model/route provenance, usage and cost, and a SHA-256 digest of the
normalized result.

**What must never be stored, in any table, under any contract:**

| Prohibited | Why |
| --- | --- |
| Chain-of-thought, thinking blocks, reasoning traces, or any provider "internal reasoning" field | Not required to reconstruct a decision — the gate rules on the proposal, never on the reasoning. Storing it multiplies the sensitive surface for no recoverable benefit and would outlive the request that produced it |
| The raw provider response body | A superset of the normalized result, carrying provider-shaped fields nothing in RAMALS reads |
| Prompts, system prompts, or the grounded context package | Reconstructible from `context_id` + `context_as_of`, which Contract A already proves survives a worker death |
| Provider credentials or API keys | Belong in the secret store, never in a row |
| Learner free text beyond what the normalized proposal schema admits | The schema is the boundary; anything outside it is not needed |

**The prohibition is structural, not procedural.** This is the discipline `V023` already
established — *"there is nothing to redact from these tables because there is nowhere to put it"* —
and Contract B inherits it rather than weakening it. The result column stores a document validated
against `diagnostic-proposal.v1` **before** it is written; a schema with no reasoning field cannot
carry reasoning. A redaction routine would be the weaker control, because it runs after the data is
already on disk.

**Retention: delete on adoption, with a hard ceiling of 30 days.** The stored result exists for one
purpose — to let a replacement worker adopt an outcome the original never recorded. The moment the
gate decision is committed, that purpose is discharged and the result is redundant with a decision
the platform already owns. It is therefore deleted **in the same transaction that commits the
adoption**, not on a timer.

The 30-day ceiling bounds the results that are never adopted, and is chosen against the provider
window rather than invented: Anthropic retains Message Batches results for 29 days, so a RAMALS
recovery window longer than that would promise recovery from a result the provider has already
dropped. Reconciliation must therefore conclude well inside it. This is deliberately far shorter
than the 400-day provenance retention in `V023`, because provenance and recoverable content are
different things with different risk: provenance is bounded metadata and is kept for release
evidence; a result is model output and is kept only until it is no longer needed.

**Encryption at rest is required** for the result column specifically, independent of any
volume-level encryption. Volume encryption protects against a stolen disk; it does nothing against
a database read by an over-broad grant, which is the realistic exposure for a table that — uniquely
in this schema — contains model output about a learner.

**Access control is narrower than the ledger's.** The runtime role receives `INSERT`, `SELECT` and
`DELETE` on the result table and nothing more; no `UPDATE`, because a stored result is immutable
once written and a result that can be rewritten is not evidence of what the provider returned.
Reporting, analytics and evaluation roles receive **no grant at all** on this table — they may read
`core.ai_execution*` provenance, which is what they actually need. The result table is not part of
the analytical surface.

**Tenant and learner isolation** is enforced the way Contract A already enforces it: every read is
keyed by the durable `request_id`, which is derived from the workflow run, which is derived from
the authenticated subject. No caller-supplied learner identifier is accepted on this path, which is
what makes reading another learner's result unreachable rather than merely checked — the same
property `DiagnosticAssessmentService` relies on today.

**Audit.** Every read of a stored result is recorded with the request identity, the reading
workload identity, and the timestamp. Writes and the adoption-time delete are recorded in the
append-only transition ledger. A result that is read and never adopted is an operationally
interesting event and must be visible as one.

### 3. `V035` and `V023` are not violated, because their invariant is per-table and stays intact

Their guarantee is precise: *these tables* cannot hold model output, enforced by the absence of any
free-text column. It is a property of `core.ai_execution`, `core.ai_execution_event` and
`core.ai_execution_dispatch`, not a claim that no table in RAMALS may ever hold a model result.

Contract B therefore **adds no column to any of them**. The dispatch ledger keeps identifiers and
timestamps only; `core.ai_execution` keeps bounded metadata and digests, including the
`response_digest` V036 added — which is a SHA-256 of the response text precisely so the response
itself need not be stored there. Every existing structural-redaction guarantee survives this ADR
unchanged, and can be re-verified by inspecting the columns.

What Contract B adds is a **separate table with a different lifecycle, a different retention, a
different grant set and a different threat model**. That separation is the reconciliation: the
tables that promise to hold no model output continue to hold none, and the one table that must hold
it is governed explicitly rather than by inheriting rules written for provenance. Merging the two
would be the actual violation — it would give a 400-day analytical surface a column that should
live for hours.

### 4. Dedicated tables, not an extension of `core.ai_execution*`

Contract B introduces its own structures rather than widening the Contract A ones:

| Table | Holds |
| --- | --- |
| `core.ai_provider_execution` | The external execution handle: provider, model, idempotency key, provider execution id, submit fence, state, timestamps |
| `core.ai_execution_result` | The normalized result, its digest, usage, and the provider execution it came from — the only table in the schema permitted to contain model output |
| `core.ai_execution_transition` | Append-only transition evidence with actor, fence and reason |
| `core.ai_reconciliation_work` | Lease, fence, attempt count and next-attempt scheduling for the reconciliation worker |

Four reasons, in order of weight. **`core.ai_execution` is the analytical and evidence surface**,
retained 400 days and read by evaluation and reporting; a result column there would put model
output behind every grant that exists for provenance. **The structural-redaction guarantee is worth
more than the convenience of one fewer table** — it is currently verifiable by reading the column
list, and that property does not survive an exception. **Lifecycles differ by three orders of
magnitude**: provenance is kept for a year and change, a result until the next commit. And
**Contract A must remain byte-identical**; a widened shared table is a change to the tables its
qualification already covers, and re-qualifying S1–S4 to add a Contract B column is a cost with no
benefit.

`core.ai_execution` remains the terminal record for both contracts. Contract B's tables reference
the same `request_id`, so one request identity still resolves to one execution row, one lifecycle
stream and — under Contract B only — one provider execution and at most one result.

### 5. Contract A is unchanged, and no historical execution becomes recoverable

**Contract A remains fully supported and is the default.** Every route is on Contract A, its state
machine is untouched, `V035`/`V036` remain its authority, and the S1–S4 qualification remains valid
because nothing this ADR decides alters a table, column, constraint or code path it exercised.

**Historical `INDETERMINATE` executions are never retroactively recoverable.** A Contract A
`INDETERMINATE` row records that the provider outcome was unknowable — there is no provider
execution identity to look up and no result to retrieve, because neither ever existed. Reinterpreting
those rows would send a recovery worker looking for an execution no provider has ever heard of, and
would convert an honest terminal state into a false promise. `LEGACY_INDETERMINATE` already encodes
exactly this treatment for the pre-`V035` generation; the same rule applies here, permanently. This
is not a migration convenience to be revisited later.

**The execution contract is bound explicitly, per route and per request.** The route table decides
under M2-ADR-014, as M2-ADR-016 §4 requires; the contract in force is then recorded durably on the
request at commission time and never changes for that request identity. A request commissioned
under A is never completed under B, and the reverse is equally forbidden. There is no inference, no
default-by-omission and no silent degradation — an adapter that cannot honour Contract B fails
rather than falling back.

### 6. Implementation gate — what must be true before V037 or any Contract B code

This ADR authorizes **no schema, no migration, no endpoint, no worker and no route change**.
Accepting it settles ownership and storage policy; it does not start construction. Every item below
must be satisfied and recorded before the first Contract B implementation PR:

1. ~~**Provider capability proven in a non-production environment**, against the M2-ADR-016
   mandatory rows, on the specific route proposed for Contract B.~~ ✅ **Satisfied for the
   MVP/research environment, 2026-08-27** — see the [prerequisite 1 qualification evidence](../release/mvp2-contract-b-prerequisite-1-qualification.md). One real Anthropic Message Batch on
   `claude-sonnet-5` via the #170 adapter: durable `msgbatch_…` identity, separate-process status
   recovery by that identity alone, terminal result retrieval, exact `custom_id` correlation,
   native lifecycle and count preservation, exactly one submission, no retry or resubmission.
   **Two parts of that criterion remain unqualified and are not claimed**: the reconciliation
   sweep's duplicate-detection behaviour was not exercised, because the create acknowledgement
   arrived cleanly and the ambiguous window was never entered; and cancellation was not attempted.
   Both belong to the Contract B crash matrix rather than to this run.
2. ~~**The Contract B design document's Definition of Done is amended** to remove the
   lost-acknowledgement line M2-ADR-016 §3 identified as unachievable, restated as detection.~~
   **Satisfied 2026-08-27** — the [amended Definition of Done](../release/mvp2-contract-b-definition-of-done.md) supersedes §18. Five of its nine criteria were amended:
   the lost-acknowledgement line this prerequisite named, three more that assumed replay-safe
   admission, and one that placed durable result persistence in RAMALS-AI contrary to §1 of this
   ADR.
3. **A data-classification sign-off** for storing a normalized model result about a learner,
   naming the accountable owner, covering the encryption mechanism and the audit surface in §2.
   **Satisfied for the MVP/research environment, 2026-08-27.** [M2-ADR-018](M2-ADR-018-contract-b-result-classification-and-encryption.md) carries the classification,
   access matrix, isolation and audit rules, and records the sign-off: Sunil Pandey as Platform
   Data Owner, classification `RESTRICTED — LEARNER-DERIVED MODEL OUTPUT`. **Not satisfied for
   organisational or production deployment**, which requires reassignment and re-approval under the
   deploying organisation's governance.
4. **The encryption-at-rest mechanism is chosen and reviewable** — key custody, rotation, and what
   happens to stored results when a key is rotated. **Satisfied for the MVP/research environment.**
   [M2-ADR-018](M2-ADR-018-contract-b-result-classification-and-encryption.md) chooses application-layer envelope encryption (AES-256-GCM, request-identity AAD), rejects
   `pgcrypto` with reasons, and defines the envelope format, key versioning, rotation, the
   decrypt-old/encrypt-new decision and fail-closed semantics. **Custody is now assigned for the
   MVP/research environment** — Sunil Pandey as interim Key Custodian — and remains open for
   production, where the custodian should not also be the Data Owner.
5. **The purge mechanism exists and is testable**, both the on-adoption delete and the ceiling
   sweep. `V023` set the precedent: a policy with no mechanism is *"a comment pretending to be a
   control"*. Note that the platform still has no scheduler, so the ceiling sweep is a function an
   operator or job runs, and shipping it as such is the honest form.
6. **T15 scenario definitions for the Contract B crash matrix are agreed**, including the
   cost-evidence scenario proving one logical request produced one billed provider execution across
   induced faults — the guarantee that actually distinguishes Contract B from a bigger schema.
7. **Contract A qualification remains green** on the candidate that carries the Contract B schema.
   S1–S4 re-run and pass, proving the additive change did not disturb the completed guarantee.

Prerequisites 1–5 gate `V037`. Prerequisites 6–7 gate any Contract B route activation, which
M2-ADR-016 §6 rule 6 already holds as a separate decision from this one.

## Rejected alternatives

- **Give RAMALS-AI its own PostgreSQL database.** What the design document draws. It reverses
  M2-ADR-008 and M2-ADR-012, defeats a test that exists to prevent exactly this, and creates a
  second authority over learner-derived durable state. The AI plane would then own data the
  platform is accountable for.
- **Add a result column to `core.ai_execution`.** One fewer table, and it destroys the structural
  redaction guarantee that makes the existing ledger auditable by reading its column list. It would
  also place model output behind every grant and every day of a 400-day analytical retention.
- **Store the raw provider response.** Superficially the safest thing to keep, and the worst: a
  superset of what any RAMALS component reads, carrying provider-shaped fields — including
  reasoning content — that nothing consumes and everything must then protect.
- **Store chain-of-thought for debuggability.** Genuinely useful when diagnosing a bad proposal, and
  still refused. The gate rules on the proposal and never on the reasoning, so it buys no
  recoverability; and reasoning about a learner is the most sensitive content the platform could
  hold, retained the longest, for the least operational return.
- **Keep results for the full 400-day provenance retention.** Consistent with the neighbouring
  tables and wrong: provenance is bounded metadata kept for release evidence, and a result is model
  output kept only until adoption. Uniform retention here would optimise for symmetry over risk.
- **Purge results only on a timer.** Simpler than a transactional delete, and it leaves every
  adopted result on disk until the sweep runs. Deleting at adoption makes the window as short as
  the mechanism allows.
- **Let Contract B reinterpret existing `INDETERMINATE` rows as recoverable.** Would appear to
  recover historical executions and would in fact poll for provider executions that never existed.
- **Defer the storage decision until implementation.** How the invariant gets broken by whoever is
  writing the migration under deadline pressure, with no ADR to point at.

## Consequences

- **Contract B becomes buildable** — the two prerequisites M2-ADR-016 left open are closed. It does
  not become built: §6 gates every line of it.
- **`core.ai_execution_result` will be the only table in the RAMALS schema permitted to contain
  model output**, and the only one requiring column-level encryption and a restricted grant set.
  That singularity is the point: one governed exception is auditable, a general relaxation is not.
- **Every existing structural-redaction guarantee survives unchanged** and remains verifiable by
  inspecting columns. `V023`'s claim about `core.ai_execution*` and `V035`'s about the dispatch
  ledger are both still literally true after Contract B ships.
- **Contract A's completed qualification is not invalidated by this ADR**, because it changes
  nothing Contract A touches. Prerequisite 7 requires re-proving that on the candidate that
  actually carries the schema, rather than assuming it.
- **The AI plane's statelessness becomes load-bearing for Contract B, not incidental to it.** The
  existing no-database test should be understood as protecting this decision, and a future proposal
  to relax it is a proposal to reopen this ADR.
- **A shorter retention makes reconciliation latency a correctness property.** If reconciliation
  routinely approached 30 days it would not merely be slow, it would be outside the window in which
  the provider still holds the result. The reconciliation SLO must be set well inside it.
- **The result table needs its own operational runbook** — what an unadopted result means, how to
  read one under audit, and what to do when the ceiling sweep finds a backlog.

## Security and data-governance rules

1. Only the normalized `diagnostic-proposal.v1` result may be persisted. Raw provider responses,
   prompts, grounded context, credentials and reasoning content may not, in any table.
2. Chain-of-thought and internal reasoning are prohibited outright. The prohibition is enforced
   structurally by validating against a schema that has no field for it, before the write.
3. Results are encrypted at rest at the column level, independent of volume encryption.
4. Results are deleted in the transaction that commits the gate decision; unadopted results are
   purged at 30 days, inside the provider's own retention window.
5. Results are immutable: `INSERT`, `SELECT`, `DELETE` for the runtime role, never `UPDATE`.
6. Reporting, analytics and evaluation roles receive no grant on the result table.
7. Reads are keyed by durable request identity derived from the authenticated subject; no
   caller-supplied learner identifier is accepted anywhere on this path.
8. Every read is audited with request identity, workload identity and timestamp. Writes and
   adoption-time deletes are recorded in the append-only transition ledger.

## Migration constraints

- **Additive only.** No Contract A table, column, constraint, trigger or grant is altered. `V035`
  and `V036` are not amended.
- **No backfill.** Contract B tables start empty. No historical execution is migrated into them.
- **No reinterpretation.** `INDETERMINATE`, `IN_FLIGHT`, `DISPATCH_OWNED` and
  `LEGACY_INDETERMINATE` rows keep their meaning permanently.
- **Contract B applies only to executions admitted after a Contract-B-capable route is active**, per
  M2-ADR-016 §5.
- **Migration numbering follows implementation order**, per ADR-0003 — the next free number at the
  time the work lands, which is `V037` only if nothing else has claimed it by then.

## Verification

This ADR is documentation and carries no implementation. Its claims are verifiable as follows:

- **§3's reconciliation** — inspect the column lists of `core.ai_execution`,
  `core.ai_execution_event` and `core.ai_execution_dispatch` after Contract B ships. Any free-text
  column on any of them contradicts this ADR.
- **§1's ownership** — `ramals-ai/tests/unit/test_no_database_access.py` and the absence of a
  PostgreSQL driver from `ramals-ai/pyproject.toml` both still hold. A Contract B change that needs
  either relaxed is out of compliance with this decision.
- **§5's Contract A guarantee** — the S1–S4 evidence bundles on candidate `b79df3b391…` remain the
  baseline, and prerequisite 7 requires them re-proven on the candidate carrying the new schema.
- **§2 and §6** — enforced by no test today, because no Contract B code exists. When it does, the
  prohibited-content rules and the on-adoption delete each require a standing test rather than a
  review convention.

## Revisit triggers

- A data-classification decision changes what may be stored about a learner.
- The provider's result-retention window changes, which moves the 30-day ceiling.
- A proposal to give the AI plane durable state of its own — which is a proposal to reopen §1, not
  an implementation detail.
- Contract A is deprecated or its default status changes, neither of which this ADR anticipates.
